package mermaid

import com.vp.plugin.VPPlugin
import com.vp.plugin.VPPluginInfo

class Plugin : VPPlugin {

    override fun loaded(vpPluginInfo: VPPluginInfo) {
        println("plugin [visual-paradigm-mermaid] is loaded ")
   }

    override fun unloaded() {
        println("plugin [visual-paradigm-mermaid] is unloaded ")
    }
}
