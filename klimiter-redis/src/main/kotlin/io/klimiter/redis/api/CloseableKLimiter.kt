package io.klimiter.redis.api

import io.klimiter.core.api.KLimiter
import java.io.Closeable

interface CloseableKLimiter :
    KLimiter,
    Closeable
