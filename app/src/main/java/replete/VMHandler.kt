package replete

import android.annotation.TargetApi
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.support.annotation.RequiresApi
import android.text.Editable
import com.eclipsesource.v8.*
import java.io.*
import java.lang.StringBuilder
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class TimeoutThread(val callback: () -> Unit, val t: Long) : Thread() {
    var isTimeoutCanceled = false
    override fun run() {
        Thread.sleep(t)
        if (!isTimeoutCanceled) {
            callback()
        }
    }
}

class IntervalThread(val callback: () -> Unit, val onCanceled: () -> Unit, val t: Long) : Thread() {
    var isIntervalCanceled = false
    override fun run() {
        while (true) {
            Thread.sleep(t)
            if (isIntervalCanceled) {
                onCanceled()
                break
            } else {
                callback()
            }
        }
    }
}

class VMHandler(
    val ht: HandlerThread,
    val sendUIMessage: (MainActivity.Messages, Any?) -> Unit,
    val bundleGetContents: (String) -> String?,
    val toAbsolutePath: (String) -> File
) : Handler(ht.looper) {
    var vm: V8? = null
    @TargetApi(Build.VERSION_CODES.KITKAT)
    @RequiresApi(Build.VERSION_CODES.KITKAT)
    override fun handleMessage(msg: Message) {
        when (msg.what) {
            MainActivity.Messages.INIT_VM.value -> vm = V8.createV8Runtime()
            MainActivity.Messages.INIT_ENV.value -> populateEnv(vm!!)
            MainActivity.Messages.BOOTSTRAP_ENV.value -> bootstrapEnv(vm!!, msg.obj as String)
            MainActivity.Messages.EVAL.value -> eval(msg.obj as String)
            MainActivity.Messages.SET_WIDTH.value -> setWidth(msg.obj as Double)
            MainActivity.Messages.CALL_FN.value -> callFn(msg.obj as V8Function)
            MainActivity.Messages.RELEASE_OBJ.value -> releaseObject(msg.obj as V8Object)
            MainActivity.Messages.RUN_PARINFER.value -> runParinfer(msg.obj as Array<*>)
        }
    }

    private fun runParinfer(args: Array<*>) {
        val s = args[0] as String
        val enterPressed = args[1] as Boolean
        val cursorPos = args[2] as Int

        val params = V8Array(vm).push(s).push(cursorPos).push(enterPressed)
        val ret = vm!!.getObject("replete").getObject("repl").executeArrayFunction("format", params)
        val text = ret[0] as String
        val cursor = ret[1] as Int

        sendUIMessage(MainActivity.Messages.APPLY_PARINFER, arrayOf(s, text, cursor))

        params.release()
        ret.release()
    }

    private fun callFn(fn: V8Function) {
        fn.call(fn, V8Array(vm))
    }

    private fun releaseObject(obj: V8Object) {
        obj.release()
    }

    private fun setWidth(width: Double) {
        vm!!.getObject("replete").getObject("repl").executeFunction("set_width", V8Array(vm).push(width))
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun bootstrapEnv(vm: V8, deviceType: String) {
        val deps_file_path = "main.js"
        val goog_base_path = "goog/base.js"

        try {
            vm.executeScript("var global = this;")

            vm.executeScript("CLOSURE_IMPORT_SCRIPT = function(src) { AMBLY_IMPORT_SCRIPT('goog/' + src); return true; }")

            val googBaseScript = bundleGetContents(goog_base_path)
            val depsScript = bundleGetContents(deps_file_path)
            if (googBaseScript != null) {
                vm.executeScript(googBaseScript)
                if (depsScript != null) {
                    vm.executeScript(depsScript)
                }
            }

            vm.executeScript("goog.isProvided_ = function(x) { return false; };")
            vm.executeScript("goog.require = function (name) { return CLOSURE_IMPORT_SCRIPT(goog.dependencies_.nameToPath[name]); };")
            vm.executeScript("goog.require('cljs.core');")
            vm.executeScript(
                "cljs.core._STAR_loaded_libs_STAR_ = cljs.core.into.call(null, cljs.core.PersistentHashSet.EMPTY, [\"cljs.core\"]);\n" +
                        "goog.require = function (name, reload) {\n" +
                        "    if(!cljs.core.contains_QMARK_(cljs.core._STAR_loaded_libs_STAR_, name) || reload) {\n" +
                        "        var AMBLY_TMP = cljs.core.PersistentHashSet.EMPTY;\n" +
                        "        if (cljs.core._STAR_loaded_libs_STAR_) {\n" +
                        "            AMBLY_TMP = cljs.core._STAR_loaded_libs_STAR_;\n" +
                        "        }\n" +
                        "        cljs.core._STAR_loaded_libs_STAR_ = cljs.core.into.call(null, AMBLY_TMP, [name]);\n" +
                        "        CLOSURE_IMPORT_SCRIPT(goog.dependencies_.nameToPath[name]);\n" +
                        "    }\n" +
                        "};"
            )

            vm.executeScript("goog.provide('cljs.user');")
            vm.executeScript("goog.require('cljs.core');")
            vm.executeScript("goog.require('replete.repl');")
            vm.executeScript("replete.repl.setup_cljs_user();")
            vm.executeScript("replete.repl.init_app_env({'debug-build': false, 'target-simulator': false, 'user-interface-idiom': '$deviceType'});")
            vm.executeScript("cljs.core.system_time = REPLETE_HIGH_RES_TIMER;")
            vm.executeScript("cljs.core.set_print_fn_BANG_.call(null, REPLETE_PRINT_FN);")
            vm.executeScript("cljs.core.set_print_err_fn_BANG_.call(null, REPLETE_PRINT_FN);")
            vm.executeScript("var window = global;")

            sendUIMessage(MainActivity.Messages.VM_LOADED, null)
            sendUIMessage(MainActivity.Messages.UPDATE_WIDTH, null)
            sendUIMessage(MainActivity.Messages.ENABLE_EVAL, null)
        } catch (e: V8ScriptExecutionException) {
            val baos = ByteArrayOutputStream()
            e.printStackTrace(PrintStream(baos, true, "UTF-8"))
            sendUIMessage(
                MainActivity.Messages.ADD_ERROR_ITEM, String(
                    baos.toByteArray(),
                    StandardCharsets.UTF_8
                )
            )
        }
    }

    private fun populateEnv(vm: V8) {
        vm.registerJavaMethod(repleteLoad, "REPLETE_LOAD")
        vm.registerJavaMethod(repletePrintFn, "REPLETE_PRINT_FN")
        vm.registerJavaMethod(amblyImportScript, "AMBLY_IMPORT_SCRIPT")
        vm.registerJavaMethod(repleteHighResTimer, "REPLETE_HIGH_RES_TIMER")
        vm.registerJavaMethod(repleteRequest, "REPLETE_REQUEST")

        vm.registerJavaMethod(repleteWriteStdout, "REPLETE_RAW_WRITE_STDOUT")
        vm.registerJavaMethod(repleteFlushStdout, "REPLETE_RAW_FLUSH_STDOUT")
        vm.registerJavaMethod(repleteWriteStderr, "REPLETE_RAW_WRITE_STDERR")
        vm.registerJavaMethod(repleteFlushStderr, "REPLETE_RAW_FLUSH_STDERR")

        vm.registerJavaMethod(repleteIsDirectory, "REPLETE_IS_DIRECTORY")
        vm.registerJavaMethod(repleteListFiles, "REPLETE_LIST_FILES")
        vm.registerJavaMethod(repleteDeleteFile, "REPLETE_DELETE")
        vm.registerJavaMethod(repleteCopyFile, "REPLETE_COPY")
        vm.registerJavaMethod(repleteMakeParentDirectories, "REPLETE_MKDIRS")

        vm.registerJavaMethod(repleteFileReaderOpen, "REPLETE_FILE_READER_OPEN")
        vm.registerJavaMethod(repleteFileReaderRead, "REPLETE_FILE_READER_READ")
        vm.registerJavaMethod(repleteFileReaderClose, "REPLETE_FILE_READER_CLOSE")

        vm.registerJavaMethod(repleteFileWriterOpen, "REPLETE_FILE_WRITER_OPEN")
        vm.registerJavaMethod(repleteFileWriterWrite, "REPLETE_FILE_WRITER_WRITE")
        vm.registerJavaMethod(repleteFileWriterFlush, "REPLETE_FILE_WRITER_FLUSH")
        vm.registerJavaMethod(repleteFileWriterClose, "REPLETE_FILE_WRITER_CLOSE")

        vm.registerJavaMethod(repleteFileInputStreamOpen, "REPLETE_FILE_INPUT_STREAM_OPEN")
        vm.registerJavaMethod(repleteFileInputStreamRead, "REPLETE_FILE_INPUT_STREAM_READ")
        vm.registerJavaMethod(repleteFileInputStreamClose, "REPLETE_FILE_INPUT_STREAM_CLOSE")

        vm.registerJavaMethod(repleteFileOutputStreamOpen, "REPLETE_FILE_OUTPUT_STREAM_OPEN")
        vm.registerJavaMethod(repleteFileOutputStreamWrite, "REPLETE_FILE_OUTPUT_STREAM_WRITE")
        vm.registerJavaMethod(repleteFileOutputStreamFlush, "REPLETE_FILE_OUTPUT_STREAM_FLUSH")
        vm.registerJavaMethod(repleteFileOutputStreamClose, "REPLETE_FILE_OUTPUT_STREAM_CLOSE")

        vm.registerJavaMethod(repleteSetTimeout, "setTimeout")
        vm.registerJavaMethod(repleteCancelTimeout, "clearTimeout")

        vm.registerJavaMethod(repleteSetInterval, "setInterval")
        vm.registerJavaMethod(repleteCancelInterval, "clearInterval")
    }

    private fun eval(s: String) {
        vm!!.getObject("replete").getObject("repl").executeFunction("read_eval_print", V8Array(vm).push(s))
        sendUIMessage(MainActivity.Messages.ENABLE_EVAL, null)
        sendUIMessage(MainActivity.Messages.ENABLE_PRINTING, null)
    }

    private var intervalId: Long = 0
    private val intervals: MutableMap<Long, IntervalThread> = mutableMapOf()

    private fun setInterval(callback: V8Function, t: Long): Long {

        if (intervalId == 9007199254740991) {
            intervalId = 0;
        } else {
            ++intervalId;
        }

        val tt = IntervalThread(
            {
                this.sendMessage(this.obtainMessage(MainActivity.Messages.CALL_FN.value, callback))
            },
            { this.sendMessage(this.obtainMessage(MainActivity.Messages.RELEASE_OBJ.value, callback)) },
            t
        )
        intervals[intervalId] = tt

        tt.start()

        return intervalId
    }

    private fun cancelInterval(tid: Long) {
        if (intervals.contains(tid)) {
            intervals[tid]!!.isIntervalCanceled = true
            intervals.remove(tid)
        }
    }

    private val repleteSetInterval = JavaCallback { receiver, parameters ->
        if (parameters.length() == 2) {
            val callback = parameters.get(0) as V8Function
            val timeout = parameters.getDouble(1).toLong()
            val tid = setInterval(callback, timeout)

            return@JavaCallback tid.toDouble()
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteCancelInterval = JavaCallback { receiver, parameters ->
        if (parameters.length() == 1) {
            val tid = parameters.getInteger(0).toLong()
            cancelInterval(tid)
        }
        return@JavaCallback V8.getUndefined()
    }

    private var timeoutId: Long = 0
    private val timeouts: MutableMap<Long, TimeoutThread> = mutableMapOf()

    private fun setTimeout(callback: V8Function, t: Long): Long {

        if (timeoutId == 9007199254740991) {
            timeoutId = 0;
        } else {
            ++timeoutId;
        }

        val tt =
            TimeoutThread({
                this.sendMessage(this.obtainMessage(MainActivity.Messages.CALL_FN.value, callback))
                this.sendMessage(this.obtainMessage(MainActivity.Messages.RELEASE_OBJ.value, callback))
            }, t)
        timeouts[timeoutId] = tt

        tt.start()

        return timeoutId
    }

    private fun cancelTimeout(tid: Long) {
        if (timeouts.contains(tid)) {
            timeouts[tid]!!.isTimeoutCanceled = true
            timeouts.remove(tid)
        }
    }

    private val repleteSetTimeout = JavaCallback { receiver, parameters ->
        if (parameters.length() == 2) {
            val callback = parameters.get(0) as V8Function
            val timeout = parameters.getDouble(1).toLong()
            val tid = setTimeout(callback, timeout)

            return@JavaCallback tid.toDouble()
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteCancelTimeout = JavaCallback { receiver, parameters ->
        if (parameters.length() == 1) {
            val tid = parameters.getInteger(0).toLong()
            cancelTimeout(tid)
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteHighResTimer = JavaCallback { receiver, parameters ->
        System.nanoTime() / 1e6
    }

    private val repleteRequest = JavaCallback { receiver, parameters ->
        if (parameters.length() == 1 && parameters.get(0) is V8Object) {
            val opts = parameters.getObject(0)

            val url = try {
                URL(opts.getString("url"))
            } catch (e: V8ResultUndefined) {
                null
            }

            val timeout = try {
                opts.getInteger("timeout") * 1000
            } catch (e: V8ResultUndefined) {
                0
            }

            val binaryResponse = try {
                opts.getBoolean("binary-response")
            } catch (e: V8ResultUndefined) {
                false
            }

            val method = try {
                opts.getString("method")
            } catch (e: V8ResultUndefined) {
                "GET"
            }

            val body = try {
                opts.getString("body")
            } catch (e: V8ResultUndefined) {
                null
            }

            val headers = try {
                opts.getObject("headers")
            } catch (e: V8ResultUndefined) {
                null
            }

            val userAgent = try {
                opts.getString("user-agent")
            } catch (e: V8ResultUndefined) {
                null
            }

            val insecure = try {
                opts.getBoolean("insecure")
            } catch (e: V8ResultUndefined) {
                false
            }

            val socket = try {
                opts.getString("socket")
            } catch (e: V8ResultUndefined) {
                null
            }

            opts.release()

            if (url != null) {
                val conn = url.openConnection() as HttpURLConnection

                conn.allowUserInteraction = false
                conn.requestMethod = method
                conn.readTimeout = timeout
                conn.connectTimeout = timeout

                if (userAgent != null) {
                    conn.setRequestProperty("User-Agent", userAgent)
                }

                if (headers != null) {
                    for (key in headers.keys) {
                        val value = headers.getString(key)
                        conn.setRequestProperty(key, value)
                    }
                }

                if (body != null) {
                    val ba = body.toByteArray()
                    conn.setRequestProperty("Content-Length", ba.size.toString())
                    conn.doInput = true;
                    conn.doOutput = true;
                    conn.useCaches = false;

                    val os = conn.outputStream
                    os.write(body.toByteArray())
                    os.close()
                }

                try {
                    conn.connect()

                    val result = V8Object(vm)

                    val responseBytes = conn.inputStream.readBytes()
                    val responseCode = conn.responseCode
                    val responseHeaders = V8Object(vm)

                    for (entry in conn.headerFields.entries) {
                        val values = StringBuilder()
                        for (value in entry.value) {
                            values.append(value, ",")
                        }
                        if (entry.key != null) {
                            responseHeaders.add(entry.key, values.toString())
                        }
                    }

                    result.add("status", responseCode)
                    result.add("headers", responseHeaders)

                    if (binaryResponse) {
                        result.add("body", V8ArrayBuffer(vm, ByteBuffer.wrap(responseBytes)))
                    } else {
                        result.add("body", String(responseBytes))
                    }

                    return@JavaCallback result
                } catch (e: Exception) {
                    val result = V8Object(vm)
                    result.add("error", e.message)
                    return@JavaCallback result
                }
            } else {
                return@JavaCallback V8.getUndefined()
            }
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteLoad = JavaCallback { receiver, parameters ->
        if (parameters.length() == 1) {
            val path = parameters.getString(0)
            return@JavaCallback bundleGetContents(path)
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val loadedLibs = mutableSetOf<String>()

    private val amblyImportScript = JavaCallback { receiver, parameters ->
        if (parameters.length() == 1) {
            var path = parameters.getString(0)

            if (!loadedLibs.contains(path)) {

                if (path.startsWith("goog/../")) {
                    path = path.substring(8, path.length)
                }

                val script = bundleGetContents(path)

                if (script != null) {
                    loadedLibs.add(path)
                    vm!!.executeScript(script)
                }
            }
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repletePrintFn = JavaCallback { receiver, parameters ->
        if (parameters.length() == 1) {
            val msg = parameters.getString(0)

            sendUIMessage(MainActivity.Messages.ADD_OUTPUT_ITEM, markString(msg))
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteWriteStdout = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val s = params.getString(0)
            System.out.write(s.toByteArray())
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteFlushStdout = JavaCallback { receiver, params ->
        System.out.flush()
        return@JavaCallback V8.getUndefined()
    }

    private val repleteWriteStderr = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val s = params.getString(0)
            System.err.write(s.toByteArray())
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteFlushStderr = JavaCallback { receiver, params ->
        System.err.flush()
        return@JavaCallback V8.getUndefined()
    }

    private val repleteIsDirectory = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)
            return@JavaCallback toAbsolutePath(path).isDirectory
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteListFiles = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)
            val ret = V8Array(vm)

            toAbsolutePath(path).list().forEach { p -> ret.push(p.toString()) }

            return@JavaCallback ret
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteDeleteFile = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            try {
                toAbsolutePath(path).delete()
            } catch (e: IOException) {
                sendUIMessage(MainActivity.Messages.ADD_ERROR_ITEM, e.toString())
            }

        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteCopyFile = JavaCallback { receiver, params ->
        if (params.length() == 2) {
            val fromPath = params.getString(0)
            val toPath = params.getString(1)
            val fromStream = toAbsolutePath(fromPath).inputStream()
            val toStream = toAbsolutePath(toPath).outputStream()

            try {
                fromStream.copyTo(toStream)
                fromStream.close()
                toStream.close()
            } catch (e: IOException) {
                fromStream.close()
                toStream.close()
                sendUIMessage(MainActivity.Messages.ADD_ERROR_ITEM, e.toString())
            }

        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteMakeParentDirectories = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)
            val absPath = toAbsolutePath(path)

            try {
                if (!absPath.exists()) {
                    absPath.mkdirs()
                }
            } catch (e: Exception) {
                sendUIMessage(MainActivity.Messages.ADD_ERROR_ITEM, e.toString())
            }

        }
        return@JavaCallback V8.getUndefined()
    }

    private val openOutputStreams = mutableMapOf<String, FileOutputStream>()

    private val repleteFileOutputStreamOpen = JavaCallback { receiver, params ->
        if (params.length() == 2) {
            val path = params.getString(0)
            val append = params.getBoolean(1)

            openOutputStreams[path] = FileOutputStream(toAbsolutePath(path), append)

            return@JavaCallback path
        } else {
            return@JavaCallback "0"
        }
    }

    private val repleteFileOutputStreamWrite = JavaCallback { receiver, params ->
        if (params.length() == 2) {
            val path = params.getString(0)
            val bytesArray = params.getArray(1)

            try {
                val bytes = ByteArray(bytesArray.length())
                for (idx in 0 until bytes.size - 1) {
                    bytes[idx] = bytesArray[idx] as Byte
                }
                openOutputStreams[path]!!.write(bytes)
            } catch (e: Exception) {
                return@JavaCallback e.message
            }
            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback "This functions accepts 2 arguments"
        }
    }

    private val repleteFileOutputStreamFlush = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            try {
                openOutputStreams[path]!!.flush()
            } catch (e: Exception) {
                return@JavaCallback e.message
            }
            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback "This functions accepts 1 argument"
        }
    }

    private val repleteFileOutputStreamClose = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            try {
                openOutputStreams[path]!!.close()
                openOutputStreams.remove(path)
            } catch (e: Exception) {
                return@JavaCallback e.message
            }

            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback "This functions accepts 1 argument"
        }
    }

    private val openWriteFiles = mutableMapOf<String, OutputStreamWriter>()

    private val repleteFileWriterOpen = JavaCallback { receiver, params ->
        if (params.length() == 3) {
            val path = params.getString(0)
            val append = params.getBoolean(1)
            val encoding = params.getString(2)

            openWriteFiles[path] =
                    FileOutputStream(toAbsolutePath(path), append).writer(Charsets.UTF_8)

            return@JavaCallback path
        } else {
            return@JavaCallback "0"
        }
    }

    private val repleteFileWriterWrite = JavaCallback { receiver, params ->
        if (params.length() == 2) {
            val path = params.getString(0)
            val content = params.getString(1)

            try {
                openWriteFiles[path]!!.write(content)
            } catch (e: Exception) {
                return@JavaCallback e.message
            }
            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback "This functions accepts 2 arguments"
        }
    }

    private val repleteFileWriterFlush = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            try {
                openWriteFiles[path]!!.flush()
            } catch (e: Exception) {
                return@JavaCallback e.message
            }
            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback "This functions accepts 1 argument"
        }
    }

    private val repleteFileWriterClose = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            try {
                openWriteFiles[path]!!.close()
                openWriteFiles.remove(path)
            } catch (e: Exception) {
                return@JavaCallback e.message
            }

            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback "This functions accepts 1 argument"
        }
    }

    private val openInputStreams = mutableMapOf<String, FileInputStream>()

    private val repleteFileInputStreamOpen = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)
            openInputStreams[path] = toAbsolutePath(path).inputStream()

            return@JavaCallback path
        } else {
            return@JavaCallback "0"
        }
    }

    private val repleteFileInputStreamRead = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)
            val bytes = ByteArray(1024)
            val bytesWritten = openInputStreams[path]!!.read(bytes)

            if (bytesWritten == -1) {
                return@JavaCallback V8.getUndefined()
            } else {
                val ret = V8Array(vm)
                bytes.forEach { b -> ret.push(b) }
                return@JavaCallback ret
            }
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteFileInputStreamClose = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            openInputStreams[path]!!.close()
            openInputStreams.remove(path)

            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val openReadFiles = mutableMapOf<String, InputStreamReader>()

    private val repleteFileReaderOpen = JavaCallback { receiver, params ->
        if (params.length() == 2) {
            val path = params.getString(0)
            val encoding = params.getString(1)

            openReadFiles[path] = toAbsolutePath(path).inputStream().reader(Charsets.UTF_8)

            return@JavaCallback path
        } else {
            return@JavaCallback "0"
        }
    }

    private val repleteFileReaderRead = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)
            val content = openReadFiles[path]!!.read()

            if (content == -1) {
                return@JavaCallback V8Array(vm).push(V8.getUndefined()).push(V8.getUndefined())
            } else {
                return@JavaCallback V8Array(vm).push(content.toChar().toString()).push(V8.getUndefined())
            }
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteFileReaderClose = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            openReadFiles[path]!!.close()
            openReadFiles.remove(path)

            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }
}
