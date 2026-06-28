package io.github.rodrigogurgel.klimiter.config

import io.github.rodrigogurgel.klimiter.core.application.LocalBudget
import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.policy.Capacity
import io.github.rodrigogurgel.klimiter.core.policy.Policy
import io.github.rodrigogurgel.klimiter.core.policy.RateLimitUnit
import io.github.rodrigogurgel.klimiter.core.port.outbound.Clock
import io.github.rodrigogurgel.klimiter.support.InMemoryGlobalCounter
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/** Cobre o ciclo de vida do runner de evicção (§4.2): a varredura periódica remove os buckets vencidos. */
class EvictionRunnerTest {
    private val policy = Policy(Capacity(100), RateLimitUnit.MINUTE)

    @Test
    fun `the periodic sweep removes expired buckets and stop cancels it`() {
        val budget = LocalBudget(InMemoryGlobalCounter(), "klimiter")
        // janela [0,60), expira em 60s
        budget.bucketFor(Dimension("u"), DimensionValue("v"), policy, nowEpochSecond = 0)
        assertEquals(1, budget.size())

        // relógio em 120s (> fim da janela) → o bucket está vencido; intervalo curto p/ o teste ser rápido
        val runner = EvictionRunner(budget, Clock { 120_000 }, EvictionProperties(Duration.ofMillis(30)))
        runner.start()
        try {
            var waited = 0
            while (budget.size() > 0 && waited < 2_000) {
                Thread.sleep(30)
                waited += 30
            }
            assertEquals(0, budget.size())
        } finally {
            runner.stop()
        }
    }
}
