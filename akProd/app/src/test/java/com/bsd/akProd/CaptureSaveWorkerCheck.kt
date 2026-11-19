package com.bsd.akProd

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object CaptureSaveWorkerCheck {
    @JvmStatic fun main(args: Array<String>) {
        val id=UUID.randomUUID().toString()
        val started=CountDownLatch(1)
        val release=CountDownLatch(1)
        val calls=AtomicInteger()
        val work=CaptureSaveWorker.submit(id) { task ->
            calls.incrementAndGet(); task.phase.set("테스트 저장 중"); started.countDown()
            check(release.await(3,TimeUnit.SECONDS))
        }
        check(started.await(3,TimeUnit.SECONDS))
        check(!work.result.isDone)
        val retry=CaptureSaveWorker.submit(id) { error("동일 요청을 중복 실행하면 안 됩니다.") }
        check(work===retry)
        check(work.phase.get()=="테스트 저장 중")
        release.countDown()
        work.result.get(3,TimeUnit.SECONDS)
        check(calls.get()==1)
        check(CaptureSaveWorker.submit(id) { error("완료 건 재실행 금지") }===work)
        val failedId=UUID.randomUUID().toString()
        val failure=CaptureSaveWorker.submit(failedId) { error("DB 연결 실패 테스트") }
        check(runCatching { failure.result.get(3,TimeUnit.SECONDS) }.isFailure)
        val second=CaptureSaveWorker.submit(failedId) { it.phase.set("재시도") }
        check(second!==failure)
        second.result.get(3,TimeUnit.SECONDS)
        check(second.result.isDone && !second.result.isCompletedExceptionally)
        val linkage=CaptureSaveWorker.submit(UUID.randomUUID().toString()) { throw NoClassDefFoundError("Android test missing API") }
        val cause=runCatching { linkage.result.get(3,TimeUnit.SECONDS) }.exceptionOrNull()?.cause
        check(cause is IllegalStateException)
        check(cause?.cause is NoClassDefFoundError)
        check(linkage.result.isCompletedExceptionally)
        val stalledId=UUID.randomUUID().toString()
        val gate=CountDownLatch(1)
        val stalledStarted=CountDownLatch(1)
        val stalled=CaptureSaveWorker.submit(stalledId) { stalledStarted.countDown(); check(gate.await(5,TimeUnit.SECONDS)) }
        check(stalledStarted.await(3,TimeUnit.SECONDS))
        check(CaptureSaveWorker.submit(stalledId) { error("일반 재시도는 기존 작업을 공유해야 합니다.") }===stalled)
        stalled.allowRetry()
        val recovered=CaptureSaveWorker.submit(stalledId) { it.phase.set("새 연결로 결과 재확인") }
        check(recovered!==stalled)
        recovered.result.get(3,TimeUnit.SECONDS)
        check(!stalled.result.isDone)
        check(CaptureSaveWorker.submit(stalledId) { error("완료된 요청 재실행 금지") }===recovered)
        gate.countDown(); stalled.result.get(3,TimeUnit.SECONDS)
        check(CaptureSaveWorker.submit(stalledId) { error("옛 작업이 새 결과를 덮으면 안 됩니다.") }===recovered)
        println("Capture save worker: 18 checks passed; timeout retry uses a fresh task while normal retries and successful results remain deduplicated")
    }
}
