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
import lesson3.B
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

class GameServer{

    // Размер карты
    private val minX = -5
    private val maxX = 5
    private val minZ = -4
    private val maxZ = 4

    // Статичные стены
    private val baseBlockedCells = setOf(
        GridPos(-1, 1),
        GridPos(0, 1),
        GridPos(1, 1),
        GridPos(1, 0),
    )
    // Дверь
    private val doorCell = GridPos(0, -3)

    // Список объектов мира
    val worldObjects = listOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3,
            0,
            1.7f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3,
            0,
            1.7f
        ),
        WorldObjectDef(
            "reward_chest",
            WorldObjectType.CHEST,
            0,
            3,
            1.3f
        ),
        WorldObjectDef(
            "door",
            WorldObjectType.DOOR,
            0,
            -3,
            1.3f
        )
    )

    // Поток событий
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val event: SharedFlow<GameEvent> = _events.asSharedFlow()

    // Поток команд
    private val  _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val command: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _commands.tryEmit(cmd)
    // tryEmit - это быстрый способ отправить команду(без корутины)

    private val _player = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )

    val players: StateFlow<Map<String, PlayerState>> = _player.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope){
        // Сервер слушает команды и выполняет их

        scope.launch {
            command.collect{ cmd ->
                progressCommand(cmd)
            }
        }
    }

    fun getPlayerData(playerId: String): PlayerState {
        return _player.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun setPlayerData(playerId: String, newData: PlayerState){
        val map = _player.value.toMutableMap()
        map[playerId] = newData
        _player.value = map.toMap()
    }

    fun updatePlayer(playerId: String, changed: (PlayerState) -> PlayerState){
        val oldMap = _player.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = changed(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _player.value = newMap.toMap()
    }

    private fun isCellInsideMap(x: Int, z: Int): Boolean{
        return x in minX..maxX && z in minZ..maxZ
    }

    private fun isCellBlockedForPlayer(player: PlayerState, x: Int, z: Int): Boolean{
        if (GridPos(x,z) in baseBlockedCells) return true

        if (!player.doorOpened && x == doorCell.x && z == doorCell.z) return true

        return false
    }

    private fun isObjectAvailableForPlayer(obj: WorldObjectDef, player: PlayerState): Boolean{
        return when(obj.type){
            WorldObjectType.ALCHEMIST -> true
            WorldObjectType.HERB_SOURCE -> true
            WorldObjectType.DOOR -> true
            WorldObjectType.CHEST -> {
                player.questState == QuestState.GOOD_END && !player.chestLooted
            }
        }
    }

    // проверка на то, смотрит ли игрок на объект с которым взаемодействует

    private fun isObjectInFrontPlayer(player: PlayerState, obj: WorldObjectDef): Boolean{
        // Facing.LEFT -> объект должен быть слева (dx < 0)
        // RIGHT dx > 0
        FORWARD dx < 0
        BACK dx > 0

        val dx = obj.cellX - player.gridX
    }

    // поиск объекта ближайшего, в чью зону, попадет игрок
    private fun nearestObject(player: PlayerState): WorldObjectDef?{
        val candidates = worldObjects.filter { obj ->
            distance2d(player.gridX.toFloat(), player.gridZ.toFloat(), obj.cellX.toFloat(), obj.cellZ.toFloat()) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->
            distance2d(player.gridX.toFloat(), player.gridZ.toFloat(), obj.cellX.toFloat(), obj.cellZ.toFloat())
        }
        // minByOrNull - минимальное из возможных или null (взять ближайший объект по расстоянию по игрока)
        // OrNull - если список этих объектов пуст - вернуть null
    }

    private suspend fun refreshPlayerArea(playerId: String){

        val player = getPlayerData(playerId)
        val nearObject = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearObject?.id

        if (oldAreaId == newAreaId){
            val newHint =
                when(newAreaId){
                    "alchemist" -> "Нажми для взаимодействия"
                    "herb_source" -> "Нажми для сбора травы"
                    else -> "Подойди к объекту"
                }
            updatePlayer(playerId) {player -> player.copy(hintText = newHint) }
            return


//            newHint = "Ты находися в зоне Алхимика, поговори с ним чтобы принять квест"
//        }else if(newArea == "herb_source"){
//            newHint = "Ты находися в зоне Источника травы, собери траву для алхимика"
//        }else{
//            newHint = "Вы не находитесь в зоне активности, подойдите к новому объекту"
        }
        if (oldAreaId != null){
            _events.emit(LeftArea(playerId, oldAreaId))
        }
        if (newAreaId != null){
            _events.emit(LeftArea(playerId, newAreaId))
        }

        val newHint =
            when(newAreaId){
                "alchemist" -> "Нажми для взаимодействия"
                "herb_source" -> "Нажми для сбора травы"
                "chest" -> "Нажми для того чтобы открыть"
                else -> "Подойди к объекту"
            }
        updatePlayer(playerId){p ->
            p.copy(
                currentAreaId = newAreaId,
                hintText = newHint
            )
        }


    }

    // Метод refreshPlayerArea (playerId: String)
    // Должен пересчитывать в какой зоне сейчас находиться игрок
    // Вам нужно получить игрока
    // Получить ближайших объектов
    // сохранить старое состояние игрока в какой зоне он был ранее
    // получить id зоны в которую он попал теперь new

    // сравнение новой зоны со старой
    // в зависимости от того в какой зоне он находиться в newHint вернуть текст для зоны alchemist и зоны herb_source
    // после обновляем игрока (свойство hintText)

    private suspend fun progressCommand(cmd: GameCommand){
        when(cmd){
            is CmdCooseDialogueOption -> {
                val p = getPlayerData(cmd.playerId)

                if(p.currentAreaId != "alchemist"){
                    _events.emit(ServerMessage(cmd.playerId, "Ты отошёл слишком далеко от Алхимика"))
                    return
                }

                when(cmd.optionId){
                    "accept_help" -> {
                        if (p.questState != QuestState.START){
                            _events.emit(ServerMessage(cmd.playerId, "Путь пока не доступен, начни диалог"))
                            return
                        }

                        updatePlayer(cmd.playerId){p ->
                            p.copy(questState = QuestState.WAIT_HERB)
                        }

                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик дал тебе задание с травой"))
                    }
                    "threat" -> {
                        if (p.questState != QuestState.START){
                            _events.emit(ServerMessage(cmd.playerId, "Сначала поговори"))
                            return
                        }

                        updatePlayer(cmd.playerId){p ->
                            p.copy(questState = QuestState.BAD_END)
                        }
                    }
                    "give_herb" -> {
                        if (p.questState != QuestState.WAIT_HERB) return

                        val herbs = herbCount(p)

                        if (herbs < 4){
                            return
                        }

                        val newCount = herbs - 4
                        val newInventory = if (newCount <= 0) p.inventory - "herb" else p.inventory + ("herb" to newCount)

                        val newMemory = p.alchemistMemory.copy(
                            receivedHerb = true,

                            )
                        updatePlayer(cmd.playerId){p ->
                            p.copy(
                                inventory = newInventory,
                                questState = QuestState.GOOD_END,
                                alchemistMemory = newMemory
                            )
                        }

                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.GOOD_END))
                        _events.emit(ServerMessage(cmd.playerId, "Квест Алхимика успешно завершен"))
                    }
                    else -> {
                        _events.emit(ServerMessage(cmd.playerId, "Неизвестный вариант диалога"))
                    }
                }
            }
            is CmdInteract -> {
                val p = getPlayerData(cmd.playerId)
                val nearObject = nearestObject(p)

                if (nearObject == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов"))
                    return
                }

                when(nearObject.type){
                    WorldObjectType.ALCHEMIST ->{
                        val oldMemory = p.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timeTalked = oldMemory.timeTalked + 1
                        )

                        updatePlayer(cmd.playerId){p ->
                            p.copy(alchemistMemory = newMemory)
                        }
                        _events.emit(InteractedWithNpc(cmd.playerId, nearObject.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        if (p.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Тебе сейчас незачем эта трава"))
                            return
                        }

                        val oldCount = herbCount(p)
                        val newCount = oldCount + 1
                        val newInventory = p.inventory + ("herb" to newCount)

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(inventory = newInventory)
                        }

                        _events.emit(InteractedWithHerbSource(cmd.playerId, nearObject.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }

                    WorldObjectType.CHEST ->{
                        setPlayerData(cmd.playerId, p.copy(gold = p.gold + 20))

                        _events.emit(ServerMessage(cmd.playerId, "Вы открыли сундук с сокровищами"))
                    }
                }
            }
            is CmdMovePlayer -> {
                updatePlayer(cmd.playerId){p ->
                    p.copy(
                        posX = p.posX + cmd.dx,
                        posZ = p.posZ + cmd.dz
                    )
                }
                refreshPlayerArea(cmd.playerId)
            }
            is CmdSwitchActivePlayer -> {
                // Дома
                val oldPlayer = getPlayerData(cmd.playerId)
                setPlayerData(cmd.playerId, oldPlayer)
                _events.emit(ServerMessage(cmd.playerId, "Активный игрок сменен"))
            }
            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { _ -> initialPlayerState(cmd.playerId) }
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен до заводских настроек"))
            }
        }
    }
}



fun herbCount(player: PlayerState): Int{
    return player.inventory["herb"] ?: 0
}






















fun main() = KoolApplication {

}
