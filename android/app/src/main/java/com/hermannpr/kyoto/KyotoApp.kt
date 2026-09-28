package com.hermannpr.kyoto

import android.app.Application
import com.hermannpr.kyoto.work.JobManager

class KyotoApp : Application() {
    val jobs: JobManager by lazy { JobManager(this) }
}
