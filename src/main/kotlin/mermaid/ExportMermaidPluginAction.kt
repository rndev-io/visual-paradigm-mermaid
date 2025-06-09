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
import com.vp.plugin.model.INOTE
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
        // Helper function for diagram type identification
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
        val participants = mutableListOf<String>()
        
        diagram.toDiagramElementArray()
                .filter({ it ->
                    it.modelElement is IInteractionLifeLine || it.modelElement is IInteractionActor
                })
                .sortedBy({ it.x })
                .forEach { it ->
                    when (it.modelElement) {
                        is IInteractionLifeLine -> {
                            val name = it.modelElement.name
                            participants.add(name)
                            writer.write("participant $name\n")
                        }
                        is IInteractionActor -> {
                            val name = it.modelElement.name
                            participants.add(name)
                            writer.write("actor $name\n")
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

        // Получаем элементы сообщений для определения позиций
        val messageElements = diagram.toDiagramElementArray(IModelElementFactory.MODEL_TYPE_MESSAGE)
        
        // Создаем структуру данных для заметок, привязанных к сообщениям
        data class MessageNote(val text: String, val messageId: String, val participantName: String)
        val messageNotes = mutableListOf<MessageNote>()
        val allElements = diagram.toDiagramElementArray()
        
        allElements.forEach { element ->
            val modelElement = element.modelElement
            val modelType = modelElement?.modelType ?: "null"
            
            if (modelType == "NOTE") {
                val noteText = modelElement?.description?.trim() ?: modelElement?.name?.trim() ?: ""
                if (noteText.isNotEmpty()) {

                    
                    // Check relationships using Visual Paradigm API
                    var attachedToMessage: IMessage? = null
                    try {
                        if (modelElement is INOTE) {
                            val note = modelElement as INOTE
                            
                            // Check fromRelationship connections
                            val fromRelationships = note.toFromRelationshipArray()
                            fromRelationships?.forEach { rel ->
                                val toElement = rel.to
                                if (toElement?.modelType == "Message") {
                                    attachedToMessage = toElement as? IMessage
                                }
                            }
                            
                            // Check toRelationship connections  
                            val toRelationships = note.toToRelationshipArray()
                            toRelationships?.forEach { rel ->
                                val fromElement = rel.from
                                if (fromElement?.modelType == "Message") {
                                    attachedToMessage = fromElement as? IMessage
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Silent error handling - fall back to coordinate-based approach
                    }
                    
                    // If directly attached, use that message, otherwise find closest
                    attachedToMessage?.let { message ->
                        // Use the directly attached message
                        val bestParticipant = if (message.from.name == message.to.name) {
                            message.from.name
                        } else {
                            "${message.from.name},${message.to.name}"
                        }
                        messageNotes.add(MessageNote(noteText, message.id, bestParticipant))
                        return@forEach
                    }
                    
                    // Находим ближайшее сообщение к этой заметке
                    var closestMessage: IMessage? = null
                    var minDistance = Double.MAX_VALUE
                    var bestParticipant = if (participants.isNotEmpty()) participants[0] else "Participant"
                    
                    messages.forEach { message ->
                        val messageElement = messageElements.find { it.modelElement == message }
                        if (messageElement != null) {
                            // Вычисляем расстояние от заметки до сообщения
                            val dx = element.x - messageElement.x
                            val dy = element.y - messageElement.y
                            
                            // Приоритизируем заметки, которые находятся ниже сообщения (dy > 0)
                            // и достаточно близко по времени в последовательности
                            val yProximityBonus = if (dy > 0 && dy < 200) 0.5 else 1.0
                            val distance = Math.sqrt((dx * dx + dy * dy).toDouble()) * yProximityBonus
                            
                            if (distance < minDistance) {
                                minDistance = distance
                                closestMessage = message
                                
                                // Определяем участников для заметки на основе позиции
                                val noteX = element.x
                                
                                // Получаем всех участников с их X позициями
                                val participantsWithX = allElements.filter { 
                                    it.modelElement is IInteractionLifeLine || it.modelElement is IInteractionActor 
                                }.map { participantElement ->
                                    val participantName = when (participantElement.modelElement) {
                                        is IInteractionLifeLine -> participantElement.modelElement.name
                                        is IInteractionActor -> participantElement.modelElement.name
                                        else -> "Unknown"
                                    }
                                    Pair(participantName, participantElement.x)
                                }.sortedBy { it.second }
                                
                                // Находим между какими участниками находится заметка
                                var leftParticipant: String? = null
                                var rightParticipant: String? = null
                                
                                for (i in 0 until participantsWithX.size - 1) {
                                    val leftX = participantsWithX[i].second
                                    val rightX = participantsWithX[i + 1].second
                                    
                                    if (noteX >= leftX && noteX <= rightX) {
                                        leftParticipant = participantsWithX[i].first
                                        rightParticipant = participantsWithX[i + 1].first
                                        break
                                    }
                                }
                                
                                // Определяем лучший формат для заметки
                                bestParticipant = when {
                                    // Если заметка между двумя участниками и достаточно близко по Y
                                    leftParticipant != null && rightParticipant != null && Math.abs(dy) < 200 -> {
                                        "$leftParticipant,$rightParticipant"
                                    }
                                    // Если заметка близко к сообщению по Y и справа от него
                                    Math.abs(dy) < 50 && dx > 0 -> {
                                        message.to.name
                                    }
                                    // Для сообщений между участниками, проверяем соответствие
                                    message.from.name != message.to.name -> {
                                        // Если заметка между участниками сообщения, используем оба
                                        val fromX = participantsWithX.find { it.first == message.from.name }?.second ?: 0
                                        val toX = participantsWithX.find { it.first == message.to.name }?.second ?: 0
                                        val minMsgX = Math.min(fromX, toX)
                                        val maxMsgX = Math.max(fromX, toX)
                                        
                                        if (noteX >= minMsgX && noteX <= maxMsgX) {
                                            "${message.from.name},${message.to.name}"
                                        } else {
                                            // Иначе используем ближайшего участника
                                            val participantsByDistance = participantsWithX.map { (name, x) ->
                                                Pair(name, Math.abs(noteX - x))
                                            }.minByOrNull { it.second }
                                            
                                            participantsByDistance?.first ?: message.to.name
                                        }
                                    }
                                    // Иначе используем ближайшего участника по X координате
                                    else -> {
                                        val participantsByDistance = participantsWithX.map { (name, x) ->
                                            Pair(name, Math.abs(noteX - x))
                                        }.minByOrNull { it.second }
                                        
                                        participantsByDistance?.first ?: message.to.name
                                    }
                                }
                            }
                        }
                    }
                    
                    closestMessage?.let { message ->
                        messageNotes.add(MessageNote(noteText, message.id, bestParticipant))
                    }
                }
            }
        }

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
            
            // Вставляем заметки, привязанные к этому сообщению
            messageNotes.filter { it.messageId == message.id }.forEach { note ->
                writer.write("Note over ${note.participantName}: ${escapeHTML(note.text)}\n")
            }
        }

        val clipboard = Toolkit.getDefaultToolkit().getSystemClipboard()
        clipboard.setContents(StringSelection(writer.toString()), null)
        viewManager.showMessageDialog(
                viewManager.getRootFrame(),
                "Mermaid diagram copied to clipboard (found ${messageNotes.size} notes attached to messages)"
        )
    }

    override fun update(action: VPAction, context: VPContext) {}
}
