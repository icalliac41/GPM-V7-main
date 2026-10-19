package com.gpm.virtual.xposed

object SortirVersionMapper {

    data class VersionMap(
        val version: String,
        val jobAd: String,
        val jobCardEvent: String,
        val baseJob: String,
        val timer: String,
        val autoValueBase: String,
        val state: String,
        val jobAdsImpl: String,
        val aaJobService: String,
        val jobAdValidator: String,
        val surfaceChecker: String,
        val notSurfaced: String,
        val jobQueueManager: String,
        val jobCardRepo: String,
        val jobCardV2ViewModel: String,
        val jobAdHandler: String,
        val jobAdViewModel: String,
        val bottomSheetUsecase: String,
        val dispatcherV2: String,
        val dispatcherProvider: String,
        val dispatcherEventV2: String,
        // ⭐ REKOMENDASI ALL-STAR: p0 (prioritas) → p → q (fallback)
        val manualAcceptMethod: String = "p0",
        val manualAcceptFallback: String = "p",
    )

    private val V5_457_1 = VersionMap(
        version = "5.457.1",
        jobAd                = "com.grab.driver.job.ad.model.JobAd",
        jobCardEvent         = "com.grab.driver.job.ad.model.JobCardEvent",
        baseJob              = "com.grab.driver.job.model.BaseJob",
        timer                = "com.grab.driver.job.ad.model.Timer",
        autoValueBase        = "com.grab.driver.job.model.\$AutoValue_BaseJob",
        state                = "com.grab.driver.job.State",
        jobAdsImpl           = "com.grab.driver.job.ad.service.JobAdsServiceImpl",
        aaJobService         = "com.grab.driver.aa.service.AAJobService",
        jobAdValidator       = "com.grab.driver.job.ad.ui.JobAdValidator",
        surfaceChecker       = "com.grab.driver.job.ad.queue.JobSurfaceabilityCheckerImpl",
        notSurfaced          = "com.grab.driver.job.ad.queue.NotSurfacedReason",
        jobQueueManager      = "com.grab.driver.job.ad.queue.JobQueueManager",
        jobCardRepo          = "com.grab.driver.job.ad.ui.jobcard.JobCardDataRepoImpl",
        jobCardV2ViewModel   = "com.grab.driver.job.ad.ui.jobcard.JobCardV2ViewModel",
        jobAdHandler         = "com.grab.driver.job.ad.ui.JobAdHandlerImpl",
        jobAdViewModel       = "com.grab.driver.aa.ui.jobad.JobAdViewModel",
        bottomSheetUsecase   = "com.grab.driver.job.ad.ui.bottomsheet.JobAdBottomSheetUsecase",
        dispatcherV2         = "com.grab.driver.job.transit.component.DisplayJobDispatcherV2Impl",
        dispatcherProvider   = "com.grab.driver.job.transit.component.DisplayJobDispatcherProviderFactory",
        dispatcherEventV2    = "com.grab.driver.job.transit.component.DisplayJobDispatcherEventV2",
        // ⭐ REKOMENDASI ALL-STAR: tap=p0, fallback=p
        manualAcceptMethod   = "p0",
        manualAcceptFallback = "p",
    )

    private val ALL: List<VersionMap> = listOf(V5_457_1)

    fun detect(classLoader: ClassLoader): VersionMap? {
        for (map in ALL) {
            if (classExists(map.surfaceChecker, classLoader) &&
                classExists(map.jobAdsImpl, classLoader) &&
                classExists(map.jobAd, classLoader)
            ) {
                return map
            }
        }
        return null
    }

    fun default(): VersionMap = V5_457_1

    private fun classExists(name: String, cl: ClassLoader): Boolean = try {
        Class.forName(name, false, cl)
        true
    } catch (_: Throwable) {
        false
    }
}