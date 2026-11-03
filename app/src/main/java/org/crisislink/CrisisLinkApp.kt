package org.crisislink

import android.app.Application
import org.crisislink.mesh.MeshRuntime

class CrisisLinkApp : Application() {
    val runtime: MeshRuntime by lazy { MeshRuntime(this) }
}
