package com.dante.zeekrcapabilitylab.preflight.continuous

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** File opening/journaling must not stall the camera's OES acquisition thread. One bounded job. */
internal class CameraFilePreparation<T> {
    private val worker=Executors.newSingleThreadExecutor {Thread(it,"p2-file-prepare")}
    private var pending:Future<T>?=null
    val busy get()=pending!=null
    fun start(open:()->T) {check(pending==null);pending=worker.submit<T> {open()}}
    fun poll():T? {
        val future=pending ?: return null
        if(!future.isDone)return null
        pending=null
        return try {future.get()} catch(t:java.util.concurrent.ExecutionException) {throw t.cause ?: t}
    }
    /** A timeout retains the executor and pending job; it never implies FD ownership was released. */
    fun finish(timeoutMs:Long=3_000):Boolean {worker.shutdown();return worker.awaitTermination(timeoutMs,TimeUnit.MILLISECONDS)}
}
