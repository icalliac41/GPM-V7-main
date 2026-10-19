package com.gpm.virtual.xposed

enum class SortirDecision { PASS, DROP, LOG_ONLY }

object SortirFilterEngine {

    fun decide(job: SortirJobInfo): SortirDecision {
        val cfg = SortirConfig
        if (!cfg.isFilterEnabled()) return SortirDecision.PASS

        // ⭐ REKOMENDASI ALL-STAR:
        // Order TANPA booking code HARUS dibiarkan lewat.
        // Jika di-block, server akan mengira order "tidak pernah terkirim"
        // dan akan mengulang-ulang mengirim order yang sama ke driver.
        if (job.bookingCode.isBlank()) {
            return SortirDecision.PASS
        }

        val min = cfg.getMinPrice()
        val max = cfg.getMaxPrice()
        if (min > 0f && job.price > 0f && job.price < min) return SortirDecision.DROP
        if (max > 0f && job.price > 0f && job.price > max) return SortirDecision.DROP

        val isKnownVariant = job.variant.contains("Standard") ||
                             job.variant.contains("Hemat") ||
                             job.variant.contains("XL") ||
                             job.variant.contains("Share") ||
                             job.variant.contains("Scheduled") ||
                             job.variant.contains("Special")

        if (!isKnownVariant) {
            if (!cfg.isAllowOtherServices()) return SortirDecision.DROP
            return SortirDecision.PASS
        }

        if (!cfg.isVariantAllowed(job.variant)) return SortirDecision.DROP
        if (!cfg.isVerticalAllowed(job.vertical)) return SortirDecision.DROP

        return SortirDecision.PASS
    }
}