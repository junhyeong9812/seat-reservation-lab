package com.jun.labs.mockpg

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** ADR-017 설계-3 API — 변경 호출은 주입([Faults]) → 원장([Ledger]) 순서 */
@RestController
class MockPgController(private val ledger: Ledger, private val faults: Faults) {

    data class RegisterRequest(val uid: String, val userId: Long, val amount: Long, val deadline: Instant)
    data class Body(val payment: PaymentView?, val code: String?)

    @PostMapping("/intents")
    fun register(@RequestBody r: RegisterRequest) = run("register") { ledger.register(r.uid, r.userId, r.amount, r.deadline) }

    @PostMapping("/payments/{uid}/pay")
    fun pay(@PathVariable uid: String) = run("pay") { ledger.pay(uid) }

    @PostMapping("/payments/{uid}/authorize")
    fun authorize(@PathVariable uid: String) = run("authorize") { ledger.authorize(uid) }

    @PostMapping("/payments/{uid}/capture")
    fun capture(@PathVariable uid: String) = run("capture") { ledger.capture(uid) }

    @PostMapping("/payments/{uid}/close")
    fun close(@PathVariable uid: String) = run("close") { ledger.close(uid) }

    /** 조회도 변경 호출과 같은 본문 `{payment, code}`(명세 부록 A) — 없으면 404 UNKNOWN_PAYMENT */
    @GetMapping("/payments/{uid}")
    fun get(@PathVariable uid: String): ResponseEntity<Body> =
        ledger.get(uid)?.let { ResponseEntity.ok(Body(it, null)) } ?: ResponseEntity.status(404).body(Body(null, "UNKNOWN_PAYMENT"))

    @GetMapping("/payments")
    fun list(@RequestParam(required = false) status: String?, @RequestParam(required = false) registeredBefore: Instant?) =
        ledger.list(status, registeredBefore)

    @GetMapping("/ledger")
    fun ledger() = ledger.dump()

    @PostMapping("/admin/faults")
    fun setFaults(@RequestBody c: Faults.Config): Faults.Config { faults.set(c); return faults.get() }

    @PostMapping("/admin/reset")
    fun reset(): ResponseEntity<Void> { ledger.reset(); faults.set(Faults.Config()); return ResponseEntity.noContent().build() }

    private fun run(op: String, action: () -> Ledger.Result): ResponseEntity<Body> {
        val rule = faults.rule(op)
        if (rule.delayMs > 0) Thread.sleep(rule.delayMs)
        val decision = faults.decide(op)
        if (decision == Faults.Decision.FAIL) return ResponseEntity.status(503).body(Body(null, "INJECTED_FAILURE"))
        val result = action()
        if (decision == Faults.Decision.UNKNOWN) Thread.sleep(rule.unknownDelayMs)
        return ResponseEntity.status(result.status).body(Body(result.view, result.code))
    }
}
