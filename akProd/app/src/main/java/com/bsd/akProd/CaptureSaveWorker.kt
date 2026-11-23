package com.bsd.akProd

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/** A UI timeout does not prove rollback. Keep the original task so a retry in this app session does not insert twice. */
object CaptureSaveWorker {
    class Work {
        val phase=AtomicReference("사진과 저장 내용을 준비하는 중")
        /** REQUEST_ID the server assigned, once the insert has committed. */
        val savedId=AtomicReference<String?>(null)
        val result=CompletableFuture<Unit>()
        @Volatile var timedOut=false
            private set
        fun allowRetry() { timedOut=true }
    }
    private val jobs=linkedMapOf<String,Work>()
    // A stalled driver cleanup must not hold every later request in one executor queue.
    // Bound concurrent attempts. The server has no duplicate-request check, so a retry after an app restart inserts a new row.
    private val slots=Semaphore(2)
    private val executor=Executors.newCachedThreadPool { command -> Thread(command,"capture-db-save").apply { isDaemon=true } }
    @Synchronized fun submit(id: String,action: (Work) -> Unit): Work {
        jobs[id]?.let {
            if(!it.result.isCompletedExceptionally && (!it.timedOut || it.result.isDone)) return it
        }
        if(jobs.size>=8) jobs.entries.removeAll { it.value.result.isDone }
        val work=Work(); jobs[id]=work
        if(!slots.tryAcquire()) {
            work.result.completeExceptionally(IllegalStateException("이전 DB 작업 2개의 응답이 없습니다. 앱을 강제 종료한 뒤 다시 열고, 저장자료 조회에서 먼저 확인한 뒤 등록하세요."))
            return work
        }
        try { executor.execute {
            val failure=try { action(work); null } catch(e: Throwable) { e } finally { slots.release() }
            if(failure==null) { work.phase.set("저장 완료"); work.result.complete(Unit) }
            else {
                work.result.completeExceptionally(if(failure is Exception) failure else IllegalStateException("${failure.javaClass.simpleName}: ${failure.message}",failure))
                if(failure is VirtualMachineError) throw failure
            }
        } } catch(e: RejectedExecutionException) {
            slots.release()
            work.result.completeExceptionally(e)
        }
        return work
    }
}
