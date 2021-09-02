package top.xjunz.automator

import `$android`.app.UiAutomation
import `$android`.app.UiAutomationConnection
import `$android`.hardware.input.InputManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.IAccessibilityServiceClient
import android.content.Context
import android.content.pm.IPackageManager
import android.graphics.Rect
import android.os.*
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.SEEK_SET
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.IAccessibilityManager
import rikka.shizuku.SystemServiceHelper
import top.xjunz.automator.model.Result
import top.xjunz.automator.util.Records
import top.xjunz.automator.util.formatCurrentTime
import java.io.*
import java.util.*
import kotlin.system.exitProcess


/**
 * The implementation of [IAutomatorConnection]. This class is expected to be executed in a privileged
 * process, e.g. shell and su, to perform functions normally.
 *
 * @see IAutomatorConnection
 *
 * @author xjunz 2021/6/22 23:01
 */
class AutomatorConnection : IAutomatorConnection.Stub() {

    companion object {
        private const val APPLICATION_ID = "top.xjunz.automator"
        const val TAG = "automator"
        const val MAX_RECORD_COUNT: Short = 500
    }

    private lateinit var uiAutomation: UiAutomation
    private val handlerThread = HandlerThread("AutomatorHandlerThread")
    private val handler by lazy {
        Handler(handlerThread.looper)
    }
    private var serviceStartTimestamp = -1L
    private var skippingCount = -1
    private var countFileDescriptor: ParcelFileDescriptor? = null
    private var logFileDescriptor: ParcelFileDescriptor? = null
    private var recordFileDescriptor: ParcelFileDescriptor? = null
    private val recordQueue = LinkedList<String>()
    private var firstCheckRecordIndex = -1
    private val records by lazy {
        Records(recordFileDescriptor!!.fileDescriptor)
    }

    override fun connect() {
        check(!handlerThread.isAlive) { "Already connected!" }
        try {
            log("========Start Connecting========", true)
            log(sayHello(), true)
            handlerThread.start()
            uiAutomation = UiAutomation(handlerThread.looper, UiAutomationConnection())
            uiAutomation.connect()
            startMonitoring()
            serviceStartTimestamp = System.currentTimeMillis()
            log("Monitoring started at ${formatCurrentTime()}", true)
        } catch (t: Throwable) {
            dumpError(t, true)
            exitProcess(0)
        }
    }

    private val launcherName by lazy {
        IPackageManager.Stub.asInterface(SystemServiceHelper.getSystemService("package"))
            ?.getHomeActivities(arrayListOf())?.packageName
    }

    private fun log(any: Any?, queued: Boolean) {
        (any?.toString() ?: "null").let {
            Log.i(TAG, it)
            if (queued) {
                recordQueue.add(it)
                if (recordQueue.size > MAX_RECORD_COUNT) {
                    if (firstCheckRecordIndex >= 0) {
                        //truncate check result records
                        recordQueue.removeAt(firstCheckRecordIndex)
                    } else {
                        recordQueue.removeFirst()
                    }
                }
            }
        }
    }

    private fun dumpResult(result: Result, queued: Boolean) {
        if (firstCheckRecordIndex == -1) firstCheckRecordIndex = recordQueue.size
        val sb = StringBuilder()
        sb.append("========${if (queued) "" else "Standalone "}Check Result========")
            .append("\ntimestamp: ${formatCurrentTime()}")
            .append("\nresult: $result")
        if (result.passed) {
            val injectType = if (result.getInjectionType() == Result.INJECTION_ACTION) "action" else "event"
            sb.append("\nskip: count=$skippingCount, injection type=$injectType")
        }
        log(sb.toString(), queued)
    }

    private fun dumpError(t: Throwable, queued: Boolean): String {
        val out = ByteArrayOutputStream()
        t.printStackTrace(PrintStream(out))
        out.close()
        log("========Error Occurred========", queued)
        return out.toString().also { log(it, queued) }
    }

    private fun startMonitoring() {
        uiAutomation.serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS //or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        var distinct = false
        var oldPkgName: String? = null
        uiAutomation.setOnAccessibilityEventListener listener@{ event ->
            try {
                val packageName = event.packageName?.toString() ?: return@listener
                if (oldPkgName != packageName) distinct = true
                oldPkgName = packageName
                val source = event.source ?: return@listener
                //ignore the launcher app
                if (packageName == launcherName) return@listener
                //ignore the android framework
                if (packageName == "android") return@listener
                //ignore android build-in apps
                if (packageName.startsWith("com.android")) return@listener
                //ignore the host app
                if (packageName == APPLICATION_ID) return@listener
                //start checking
                checkSource(source, monitorResult.apply { reset() }, true)
                //to avoid repeated increments, increment only when distinct
                if (monitorResult.passed && distinct) {
                    skippingCount++
                    distinct = false
                    records.putResult(monitorResult)
                }
                //should dump result after incrementing skipping count, cuz we need to
                //dump the latest count
                if (monitorResult.getReason() != Result.REASON_ILLEGAL_TARGET) {
                    dumpResult(monitorResult, true)
                }
            } catch (t: Throwable) {
                monitorResult.maskReason(Result.REASON_ERROR)
                dumpError(t, true)
            } finally {
                event.recycle()
            }
        }
    }

    /**
     * Calling [UiAutomation.disconnect] would cause an error because the uid  (host app) calling
     * [UiAutomation.connect] is not the same as the uid (shell/root) calling disconnect(). Then we
     * just bypass this check and call the final unregistering method. There is also a way to fix
     * this by calling connect() in the constructor but this would also fail when the shizuku server
     * restarts and changes its mode from root to shell.
     *
     * @see <a href="https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/UiAutomationConnection.java;l=115;bpv=0;bpt=1">
     * frameworks/base/core/java/android/app/UiAutomationConnection.java</a>
     */
    override fun disconnect() {
        check(handlerThread.isAlive) { "Already disconnected!" }
        try {
            val manager: IAccessibilityManager = IAccessibilityManager.Stub.asInterface(
                SystemServiceHelper.getSystemService(Context.ACCESSIBILITY_SERVICE)
            )
            UiAutomation::class.java.getDeclaredField("mClient").run {
                isAccessible = true
                manager.unregisterUiTestAutomationService(get(uiAutomation) as IAccessibilityServiceClient)
                set(uiAutomation, null)
            }
        } catch (t: Throwable) {
            dumpError(t, true)
        } finally {
            handlerThread.quitSafely()
        }
    }

    override fun sayHello() = "Hello from the remote service! My uid is ${Os.geteuid()} & my pid is ${Os.getpid()}"

    override fun isConnected() = handlerThread.isAlive

    override fun getStartTimestamp() = serviceStartTimestamp

    override fun getPid() = Os.getpid()

    override fun getSkippingCount() = skippingCount

    override fun setFileDescriptors(pfds: Array<ParcelFileDescriptor>?) {
        check(pfds != null && pfds.size == 3)
        countFileDescriptor = pfds[0]
        checkNotNull(countFileDescriptor)
        logFileDescriptor = pfds[1]
        checkNotNull(logFileDescriptor)
        recordFileDescriptor = pfds[2]
        checkNotNull(recordFileDescriptor)
        log("File descriptors received", true)
        try {
            FileInputStream(countFileDescriptor!!.fileDescriptor).bufferedReader().useLines {
                skippingCount = it.firstOrNull()?.toIntOrNull() ?: 0
                log("The skipping count parsed: $skippingCount", true)
            }
            records.parse().getRecordCount().also {
                if (skippingCount != it) {
                    skippingCount = it
                    log("Skipping count inconsistency detected! Reassign the skipping count to $it", true)
                }
            }
            log("The record file parsed", true)
        } catch (t: Throwable) {
            if (t is Records.ParseException) {
                log(t.message, true)
            } else {
                dumpError(t, true)
            }
        }
    }

    override fun setBasicEnvInfo(info: String?) = log(info, true)

    override fun setSkippingCount(count: Int) {
        check(count > -1)
        skippingCount = count
    }

    /**
     * A result instance for monitoring to avoid frequent object allocations.
     */
    private val monitorResult by lazy {
        Result()
    }

    private val standaloneResult by lazy {
        Result()
    }


    /**
     * Launch a standalone check.
     *
     * @param listener a listener to be notified the result
     */
    override fun standaloneCheck(listener: OnCheckResultListener) {
        handler.post {
            try {
                uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("跳过").forEach {
                    checkSource(it, standaloneResult.apply { reset() }, false)
                    //dump the result before calling the listener, cuz a marshall of result would
                    // recycle the node, hence, we could not dump it any more.
                    dumpResult(standaloneResult, false)
                    listener.onCheckResult(standaloneResult)
                }
            } catch (t: Throwable) {
                dumpError(t, false)
                standaloneResult.maskReason(Result.REASON_ERROR)
            }
        }
    }

    override fun getRecords() = records.asList()

    /**
     * Inject a mock finger click event via [InputManager] into a specific [rect], corresponding
     * to [Result.INJECTION_EVENT].
     */
    private fun injectFingerClickEvent(rect: Rect) {
        val im = InputManager.getInstance()
        val downTime = SystemClock.uptimeMillis()
        val downAction = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            rect.exactCenterX(), rect.exactCenterY(), 0
        )
        downAction.source = InputDevice.SOURCE_TOUCHSCREEN
        im.injectInputEvent(downAction, 2)
        val upAction = MotionEvent.obtain(downAction).apply { action = MotionEvent.ACTION_UP }
        im.injectInputEvent(upAction, 2)
        upAction.recycle()
        downAction.recycle()
    }

    /**
     * Launch a standalone check finding whether a [source] node contains but one single legal target
     * to be skipped.
     *
     * @param source the source node used to find the possible target
     * @param result the result of this check
     * @param inject should inject click to the detected legal target or not
     */
    private fun checkSource(source: AccessibilityNodeInfo, result: Result, inject: Boolean) {
        result.pkgName = source.packageName.toString()
        source.findAccessibilityNodeInfosByText("跳过").run {
            when (size) {
                0 -> result.maskReason(Result.REASON_ILLEGAL_TARGET or Result.REASON_MASK_PORTRAIT)
                1 -> checkNode(first(), result, inject)
                else -> result.maskReason(Result.REASON_ILLEGAL_TARGET or Result.REASON_MASK_TRANSVERSE)
            }
        }
    }

    private fun checkNode(node: AccessibilityNodeInfo, result: Result, inject: Boolean) {
        if (!node.isVisibleToUser) {
            result.maskReason(Result.REASON_INVISIBLE)
            return
        }
        if (!checkText(node.text, result)) return
        val nodeRect = Rect().also { node.getBoundsInScreen(it) }
        result.bounds = nodeRect
        val windowRect = Rect().also { node.window.getBoundsInScreen(it) }
        if (!checkRegion(nodeRect, windowRect, result)) return
        if (!checkSize(nodeRect, windowRect, result)) return
        //it's enough strict to confirm a target after all these checks, so we consider any node
        //reaching here as passed. Node click-ability is not a sufficient criteria for check.
        result.passed = true
        if (node.isClickable) {
            if (inject) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            result.maskReason(Result.REASON_MASK_NOT_CLICKABLE)
            node.parent?.run {
                val parentBounds = Rect().also { getBoundsInScreen(it) }
                result.parentBounds = parentBounds
                if (isClickable && checkSize(parentBounds, windowRect, result)) {
                    result.bounds = parentBounds
                    if (inject) performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
            //the parent has its fault when reaches this step
            result.maskReason(Result.REASON_MASK_PARENT)
            if (inject) injectFingerClickEvent(nodeRect)
        }
    }

    private fun checkText(text: CharSequence, result: Result): Boolean {
        result.text = text.toString()
        if (text.length > 6) {
            result.maskReason(Result.REASON_ILLEGAL_TEXT or Result.REASON_MASK_TRANSVERSE)
            return false
        }
        if (text.filter { it > '~' }.count() > 4) {
            result.maskReason(Result.REASON_ILLEGAL_TEXT or Result.REASON_MASK_PORTRAIT)
            return false
        }
        return true
    }

    private fun checkRegion(nodeRect: Rect, windowRect: Rect, result: Result): Boolean {
        if (/*nodeRect.exactCenterX() > windowRect.width() / 4f &&*/ nodeRect.exactCenterX() < windowRect.width() / 4f * 3) {
            result.maskReason(Result.REASON_ILLEGAL_LOCATION or Result.REASON_MASK_TRANSVERSE)
            return false
        }
        if (nodeRect.exactCenterY() > windowRect.height() / 4f && nodeRect.exactCenterY() < windowRect.height() / 3f * 2) {
            result.maskReason(Result.REASON_ILLEGAL_LOCATION or Result.REASON_MASK_PORTRAIT)
            return false
        }
        return true
    }

    private fun checkSize(nodeRect: Rect, windowRect: Rect, result: Result): Boolean {
        val nw = nodeRect.width().coerceAtLeast(nodeRect.height())
        val nh = nodeRect.width().coerceAtMost(nodeRect.height())
        val isPortrait = windowRect.width() < windowRect.height()
        result.portrait = isPortrait
        if (isPortrait) {
            if (nw == 0 || nw >= windowRect.width() / 3) {
                result.maskReason(Result.REASON_ILLEGAL_SIZE or Result.REASON_MASK_TRANSVERSE)
                return false
            }
            if (nh == 0 || nh >= windowRect.height() / 8) {
                result.maskReason(Result.REASON_ILLEGAL_SIZE or Result.REASON_MASK_PORTRAIT)
                return false
            }
        } else {
            if (nw == 0 || nw > windowRect.width() / 6) {
                result.maskReason(Result.REASON_ILLEGAL_SIZE or Result.REASON_MASK_TRANSVERSE)
                return false
            }
            if (nh == 0 || nh > windowRect.height() / 4) {
                result.maskReason(Result.REASON_ILLEGAL_SIZE or Result.REASON_MASK_PORTRAIT)
                return false
            }
        }
        return true
    }

    /**
     * Truncate the file's length to 0 and seek its r/w position to 0, namely, clear its content.
     */
    private fun truncate(pfd: ParcelFileDescriptor) {
        try {
            Os.ftruncate(pfd.fileDescriptor, 0)
            Os.lseek(pfd.fileDescriptor, 0, SEEK_SET)
        } catch (e: ErrnoException) {
            dumpError(e, false)
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            log("Service is dead at ${formatCurrentTime()}. Goodbye, world!", true)
            persistSkippingCount()
            persistRecords()
            persistLog()
        })
    }

    override fun persistLog() {
        if (recordQueue.size != 0) logFileDescriptor?.run {
            truncate(this)
            FileOutputStream(fileDescriptor).bufferedWriter().use { writer ->
                recordQueue.forEach {
                    writer.write(it)
                    writer.newLine()
                }
                writer.flush()
            }
        }
    }

    private fun persistSkippingCount() {
        if (skippingCount != -1) {
            countFileDescriptor?.run {
                truncate(this)
                ParcelFileDescriptor.AutoCloseOutputStream(this).bufferedWriter().use {
                    it.write(skippingCount.toString())
                    it.flush()
                }
            }
        }
    }

    private fun persistRecords() {
        if (!records.isEmpty()) {
            recordFileDescriptor?.run {
                truncate(this)
                ParcelFileDescriptor.AutoCloseOutputStream(this).bufferedWriter().use {
                    records.forEach { record ->
                        it.write(record.toString())
                        it.newLine()
                    }
                    it.flush()
                }
            }
        }
    }


    override fun destroy() {
        disconnect()
        exitProcess(0)
    }
}