package mermaid

import com.vp.plugin.ApplicationManager
import com.vp.plugin.ViewManager
import com.vp.plugin.action.VPAction
import com.vp.plugin.action.VPContext
import com.vp.plugin.action.VPContextActionController
import com.vp.plugin.model.IActionTypeReturn
import com.vp.plugin.model.IActionTypeSend
import com.vp.plugin.model.IInteractionActor
import com.vp.plugin.model.IInteractionLifeLine
import com.vp.plugin.model.IMessage
import com.vp.plugin.model.factory.IModelElementFactory
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.io.StringWriter

enum class SequenceNumbering(val value: Int) {
    NestedLevel(0),
    SingleLevel(1),
    FrameBasedNestedLevel(2),
    FrameBasedSingleLevel(3);

    companion object {
        fun fromInt(value: Int) = SequenceNumbering.values().first { it.value == value }
    }
}

class ExportMermaidPluginAction : VPContextActionController {
    private val viewManager: ViewManager
        get() = ApplicationManager.instance().viewManager

    fun printHelp(context: VPContext) {
        println("Diagram: ${context.diagram.type}")
    }

    fun escapeHTML(s: String): String {
        val out = StringBuilder(Math.max(16, s.length))
        for (i in 0..s.length - 1) {
            val c = s.get(i)
            if (c == '#' || c == ';') {
                out.append("#")
                out.append(c.code)
                out.append(';')
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    override fun performAction(action: VPAction, context: VPContext, event: ActionEvent) {
        val writer = StringWriter()

        writer.write("sequenceDiagram\n")

        val diagram = context.diagram

        writer.write("title: ${diagram.name}\n")

        // viewManager.showMessage(diagram.toPropertiesString())
        // lifelines
        diagram.toDiagramElementArray()
                .filter({ it ->
                    it.modelElement is IInteractionLifeLine || it.modelElement is IInteractionActor
                })
                .sortedBy({ it.x })
                .forEach { it ->
                    when (it.modelElement) {
                        is IInteractionLifeLine -> {
                            writer.write("participant ${it.modelElement.name}\n")
                        }
                        is IInteractionActor -> {
                            writer.write("actor ${it.modelElement.name}\n")
                        }
                    }
                }

        var activations = HashMap<String, ArrayList<IMessage>>()

        val sequenceNumbering =
                SequenceNumbering.fromInt(
                        diagram.getDiagramPropertyByName("sequenceNumbering").valueAsInt
                )

        // TODO: Add Fragments support
        // var fragments = HashMap<String, Pair<Int, Int>>()
        //
        // diagram.toDiagramElementArray().forEach {element ->
        //     // println("${element.modelElement.modelType}")
        //
        //     if (element.modelElement.modelType == "CombinedFragment") {
        //         val model = element.modelElement as ICombinedFragment
        //         println("FRAGMENT: ${model.name}; ${model.toPropertiesString()}")
        //
        //     }
        // }

        val messages =
                diagram.toDiagramElementArray(IModelElementFactory.MODEL_TYPE_MESSAGE)
                        .map { it.modelElement as IMessage }
                        .sortedWith(
                                when (sequenceNumbering) {
                                    SequenceNumbering.SingleLevel,
                                    SequenceNumbering.FrameBasedSingleLevel ->
                                            compareBy({ it.sequenceNumber.toInt() })
                                    SequenceNumbering.NestedLevel,
                                    SequenceNumbering.FrameBasedNestedLevel ->
                                            compareBy({ it.sequenceNumber })
                                }
                        )

        messages.forEach { message ->
            // store Activation messages
            // check first and last message in future for activating and deactivating
            val activationFrom = message.fromActivation
            val activationTo = message.toActivation

            if (activationFrom != null) {
                val list = activations.getOrDefault(activationFrom.id, arrayListOf())
                list.add(message)
                activations.set(activationFrom.id, list)
            }

            if (activationTo != null) {
                val list = activations.getOrDefault(activationTo.id, arrayListOf())
                list.add(message)
                activations.set(activationTo.id, list)
            }
        }

        messages.forEach { message ->
            var arrow = "->>"
            when (message.actionType) {
                is IActionTypeSend -> {
                    arrow = "->>"
                }
                is IActionTypeReturn -> {
                    arrow = "-->>"
                }
            }

            val lifeLineFrom = message.from
            val lifeLineTo = message.to
            val activationFrom = message.fromActivation
            val activationTo = message.toActivation

            // viewManager.showMessage(
            //         "${message.name}: ${activationFrom?.id}; ${activationTo?.id}"
            // )

            writer.write(
                    "${lifeLineFrom.name}${arrow}${lifeLineTo.name}: ${message.sequenceNumber}. ${escapeHTML(message.name ?: "")}\n"
            )

            if (activationFrom != null) {
                val msg = activations.getOrDefault(activationFrom.id, arrayListOf())
                if (msg.first() == message) {
                    writer.write("activate ${lifeLineFrom.name}\n")
                }
                if (msg.last() == message) {
                    writer.write("deactivate ${lifeLineFrom.name}\n")
                }
            }

            if (activationTo != null && activationFrom != activationTo) {
                val msg = activations.getOrDefault(activationTo.id, arrayListOf())
                if (msg.first() == message) {
                    writer.write("activate ${lifeLineTo.name}\n")
                }
                if (msg.last() == message) {
                    writer.write("deactivate ${lifeLineTo.name}\n")
                }
            }
        }

        val clipboard = Toolkit.getDefaultToolkit().getSystemClipboard()
        clipboard.setContents(StringSelection(writer.toString()), null)
        viewManager.showMessageDialog(
                viewManager.getRootFrame(),
                "Mermaid diagram copied to clipboard"
        )
        // println(writer.toString())
    }

    override fun update(action: VPAction, context: VPContext) {}
}
