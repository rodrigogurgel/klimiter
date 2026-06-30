package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

/**
 * Estratégia de teto de concorrência consultada pelo [ConcurrencyLimitInterceptor]: expõe o [current]
 * (teto vigente, lido no gate) e recebe a latência de cada request concluído via [observe] (no-op nas
 * estratégias estáticas). Permite trocar fixo ↔ adaptativo sem mexer no interceptor.
 */
interface ConcurrencyLimit {
    /** Teto de chamadas simultâneas vigente agora. */
    val current: Int

    /**
     * Amostra a sojourn latency de um request concluído (caminho de sucesso). [inFlight] é a
     * concorrência no momento (para descartar amostras sem carga); [nowMillis] dá a base de tempo da
     * janela. Default no-op: estratégias estáticas ignoram.
     */
    fun observe(rttMillis: Long, inFlight: Int, nowMillis: Long) { /* no-op: estático não se ajusta */ }
}

/** Teto fixo (modo `fixed`): [current] constante, sem auto-ajuste. */
class FixedConcurrencyLimit(override val current: Int) : ConcurrencyLimit {
    init {
        require(current > 0) { "limite fixo deve ser > 0, recebido $current" }
    }
}
