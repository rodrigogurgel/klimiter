package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Onde uma decisão de reserva foi tomada (§13): [LOCAL] = negação sem round-trip pelas regras do
 * §5.2 (o nó local só nega, nunca admite); [CENTRAL] = confirmada pelo incremento condicional (§4).
 * A razão LOCAL/CENTRAL nas negações mede a efetividade da negação local (§6.4) e o tráfego poupado
 * do armazenamento central.
 */
enum class DecisionOrigin { LOCAL, CENTRAL }
