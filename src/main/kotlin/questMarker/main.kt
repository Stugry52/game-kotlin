package questMarker

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.modules.ui2.AlignmentX
import de.fabmax.kool.modules.ui2.AlignmentY
import de.fabmax.kool.modules.ui2.Button
import de.fabmax.kool.modules.ui2.Column
import de.fabmax.kool.modules.ui2.RoundRectBackground
import de.fabmax.kool.modules.ui2.Row
import de.fabmax.kool.modules.ui2.Text
import de.fabmax.kool.modules.ui2.addPanelSurface
import de.fabmax.kool.modules.ui2.align
import de.fabmax.kool.modules.ui2.background
import de.fabmax.kool.modules.ui2.font
import de.fabmax.kool.modules.ui2.margin
import de.fabmax.kool.modules.ui2.mutableStateOf
import de.fabmax.kool.modules.ui2.onClick
import de.fabmax.kool.modules.ui2.padding
import de.fabmax.kool.modules.ui2.setupUiScene
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.scene.addColorMesh
import de.fabmax.kool.scene.defaultOrbitCamera
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import kotlinx.coroutines.coroutineScope

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow           // MutableStateFlow - радиостанция событий (мы туда)
import kotlinx.coroutines.flow.StateFlow                  // StateFlow - только чтение для подписчиков
import kotlinx.coroutines.flow.MutableSharedFlow          // MutableSharedFlow - табло состояний
import kotlinx.coroutines.flow.SharedFlow                 // SharedFlow - только чтение состояния
import kotlinx.coroutines.flow.asSharedFlow               // asSharedFlow() - отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow                // asStateFlow() - отдать наружу только StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.selects.select
import org.w3c.dom.Text
import kotlin.math.sqrt

// Flow - поток в котором иногда проходят разные значения
// событие "человек сделал квест"
// состояние игрока и тд.....

// Поток данных который выполняется параллельно другим потокам данных - корутина
// Внутри Flow код не начинает свою работу, до тех пор, пока что-то не вызовет collect
// каждый новый collect - запустит поток заново

// StateFlow - набор состояний
// Любой stateFlow - существует сам по себе. Хранит какое-то одно текущее состояние, которое меняется
// Когда появляется слушатель этого состояния он получает его нынешнее значение и все последующие обновления значения состояния

// SharedFlow - рассыльщик событий (радио, громкоговоритель)
// Идеален для рассылки всем подписчикам

// collect - значит слушать поток, и выполнять код внутри блока события
// Выполняет код, например пришло новое значение.
// collect {...} - обработчик каждого последующего сообщения

// flow.collect{ value ->
//      println(value)
// }

// collect важно запускать внутри корутины launch
// collect не завершается сам по себе

// ======== Слушатель событий ======= //

//coroutineScope.launch{
//    server.player.collect{ playersMap ->
//        // Код в случае нового состояния игрока
//    }
//}

//Пример после подписки:
// 1. Кто-то повлиял и обновил StateFlow игрока
// 2. collect увидит это, так как он уже слушает все изменения StateFlow игрока
// 3. И выполнит код внутри блока collect{playersMap -> {этот код}}

// emit - разослать событие, чтобы все collect (которые его ждут) отреагировали на это
// tryEmit - быстрая отправка события сразу буз корутины.

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class Facing{
    LEFT,
    RIGHT,
    FORWARD,
    BACK
}
enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST,
    DOOR
}
data class GridPos(
    val x: Int,
    val z: Int
)
data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val cellX: Int,
    val cellZ: Int,
    val interactRadius: Float
)
data class NpcMemory(
    val hasMet: Boolean,        // Встретился ли игрок уже с Npc
    val timeTalked: Int,        // Сколько раз поговорил
    val receivedHerb: Boolean,   // Отдали ли уже траву
)
data class PlayerState(
    val playerId: String,
    val gridX: Int,
    val gridZ: Int,
    val facing: Facing,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val gold: Int,
    val alchemistMemory: NpcMemory,
    val chestLooted: Boolean,
    val doorOpened: Boolean,
    val currentFocusId: String?,
    val hintText: String
)

fun facingToYawDeg(facing: Facing): Float{
    // Угол поворота игрока по оси Y
    return when(facing){
        Facing.FORWARD -> 0f
        Facing.RIGHT -> 90f
        Facing.BACK -> 180f
        Facing.LEFT -> 270f
    }
}

fun larp(current: Float, target: Float, t: Float): Float{
    // Линейная интерполяция - нужна для плавного перемещения объекта от 1 точки к другой
    return current + (target - current) * t
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    // Расстояние между двумя точками на плоскости XZ
    // Школьная формула расстояния:
    // sqrt((dx * dx) + (dz * dz))
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx * dx + dz * dz)
}

fun initialPlayerState(playerId: String): PlayerState{
    // Разделение на нескольких игроков

    return if (playerId == "Stas"){
        PlayerState(
            "Stas",
            0,
            0,
            Facing.FORWARD,
            QuestState.START,
            emptyMap(),
            2,
            NpcMemory(
                true,
                2,
                false,
            ),
            false,
            false,
            null,
            "Подойди к любой области на карте"
        )
    }else{
        PlayerState(
            "Oleg",
            0,
            0,
            Facing.FORWARD,
            QuestState.START,
            emptyMap(),
            2,
            NpcMemory(
                false,
                0,
                false,
            ),
            false,
            false,
            null,
            "Подойди к любой области на карте"
        )
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView {
    // Теперь показываем активный диалог только если в фокусе игрока именно Алхимик
    if (player.currentFocusId != "alchemist"){
        return DialogueView(
            "Алхимик",
            "Повернись сюда, или подойди ближе",
            emptyList()
        )
    }
    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet){
                    "Новое лицо, Я тебя не помню, зачем пришел?"
                }else{
                    "Снова ты, ${player.playerId}. Я тебя уже запомнил, ходи оглядывайся"
                }
            DialogueView(
                "Алхимик",
                "$greeting \nЕсли хочешь варить траву, для начала, собери ее 4 штуки",
                listOf(
                    DialogueOption(
                        "accept_help",
                        "Я буду варить"
                    ),
                    DialogueOption(
                        "threat",
                        "Давай сюда товар, быстро"
                    )
                )
            )
        }

        QuestState.WAIT_HERB -> {
            val beOnSource = "Не знаю где ты взял эту траву, но пускай будет."
            if (herbs < 4) {
                DialogueView(
                    "Алхимик",
                    "$beOnSource Пока ты собрал только $herbs/4 Травы. Возвращайся с полным товаром",
                    emptyList()
                )
            } else {
                DialogueView(
                    "Алхимик",
                    "$beOnSource Отличный товар, давай сюда",
                    listOf(
                        DialogueOption(
                            "give_herb",
                            "Отдать 4 травы"
                        )
                    )
                )
            }
        }
        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb){
                    "Спасибо, я теперь точно много зелий наварю, я тебя запомнил, заходи ещё"
                }else{
                    "Ты завершил квест, но память не обновилась"
                }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }

        QuestState.EVIL_END -> {
            DialogueView(
                "Алхимик",
                "Я не хочу с тобой больше разговаривать, уходи!",
                emptyList()
            )
        }
    }
}

sealed interface GameCommand{
    val playerId: String
}

data class CmdStepMove(
    override val playerId: String,
    val stepX: Int,
    val stepZ: Int
): GameCommand

// Команда взаимодействия игрока с объектом
data class CmdInteract(
    override val playerId: String
): GameCommand

// Команда выбора варианта диалога
data class CmdCooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand


// ====== События сервер к клиенту ========= //

sealed interface GameEvent{
    val playerId: String
}

data class PlayerMoved(
    override val playerId: String,
    val newGridX: Int,
    val newGridZ: Int
): GameEvent

data class MovementBlock(
    override val playerId: String,
    val blockedX: Int,
    val blockedZ: Int
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithChest(
    override val playerId: String,
    val chestId: String
): GameEvent

data class InteractedWithDoor(
    override val playerId: String,
    val doorId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent



fun herbCount(player: PlayerState): Int{
    return player.inventory["herb"] ?: 0
}






















fun main() = KoolApplication {

}
