package PlayerKeyboardMovement

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

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow           // MutableStateFlow - радиостанция событий (мы туда)
import kotlinx.coroutines.flow.StateFlow                  // StateFlow - только чтение для подписчиков
import kotlinx.coroutines.flow.MutableSharedFlow          // MutableSharedFlow - табло состояний
import kotlinx.coroutines.flow.SharedFlow                 // SharedFlow - только чтение состояния
import kotlinx.coroutines.flow.asSharedFlow               // asSharedFlow() - отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow                // asStateFlow() - отдать наружу только StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import questMarker.distance2d


// Импорты библеотеки desktop Keyboard bridge (JVM) //
import java.awt.KeyEventDispatcher
// KeyEventDispatcher - перехватчик событий клавиатуры
// То есть, как объект, который видит, что мы нажимаем на клавиатуре

import java.awt.KeyboardFocusManager
// KeyboardFocusManager -  менеджер фокуса окна(активного)
// Он нужен, чтобы добраться до системы ввода клавиатуры внутри активного окна windows например

import java.awt.event.KeyEvent
import kotlin.collections.filter
import kotlin.collections.minByOrNull

// KeyEvent - событие нажатия какой-то клавиши
// В нем хранятся:
// - какая клавиша нажата
// - нажали или отпустить ее

//--------- Математические формулы --------//
import kotlin.math.abs
// abs(x) - модуль числа x
// abs(-3) = 3

import kotlin.math.atan2
// atan2(....) - функция для вычисления угла напрвления
// Нам нужна, чтобы понять:
// "если игрок идет вот втакую сторону, под каким углом он должен смотреть?"

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
// cos - косинус
// Нужен для математики высчитывания направлений

import kotlin.math.sqrt
// Квадратный корень числа
// Нужен для длины вектора и расстояний

// Это объект, который хранит состояние клавиатуры
// Очень Важно:
// Он не двигает игрока сам по себе
// Он дает ответ на вопросы:
// - клавиша сейчас зажата?
// - клавиша только что зажали?
object DesktopKeyboardState{
    private val pressedKeys = mutableSetOf<Int>()
    // pressedKey - набор кодов клавиш, которые сейчас удерживается
    // Set - Набор уникальных чисел

    private val justPressedKeys = mutableSetOf<Int>()
    // justPressedKeys - набор клавиш, которые нажали вот вот только что
    // Удобно для действий вроде:
    // любых одиночных разовых действий(открыть дверь, начать диалог, открыть сундук и тд...)
    // Если не сделать этого, то клавиша взаемодействия E при удержании будет срабатывать каждый кадр

    private var isInstalled = false
    // isInstalled - флаг установки перехватчика клавиатуры (установлен ли?)
    // Зачем нужен?:
    // Если вызывать install() 10 раз - можно случайно или специально повесить 10 одинаковых слушателей
    // Из-за чего срабатывания будет накладываться и все будет "кликаться" несколько раз

    fun install(){
        // install - установка слушаетля клавиатуры
        // Делать это будем 1 раз в самом начале программы

        if (isInstalled) return

        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(
                object : KeyEventDispatcher{
                    // object : Type {...} - это создание анонимного объекта
                    // который сразу реализовать интерфейс KeyEventDispatcher
                    // Простыми словами: "Создать объект-перехватчик клавиатуры, прямо сейчас"

                    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                        // dispatchKeyEvent(...) - метод, который будет вызываться при событии нажатия на клавишу
                        when(e.id){
                            // Проверяем тип клавиши
                            KeyEvent.KEY_PRESSED -> {
                                // KEY_PRESSED - клавиша нажата
                                if (!pressedKeys.contains(e.keyCode)){
                                    // contains() - проверка на то, содержится ли клавиша уже в наборе клавиш, что нажаты
                                    justPressedKeys.add(e.keyCode)
                                    // keyCode - код клавиши клавиатуры
                                }

                                pressedKeys.add(e.keyCode)
                            }

                            KeyEvent.KEY_RELEASED -> {
                                // KEY_RELEASED - событие, когда клавишу отпустили
                                pressedKeys.remove(e.keyCode)
                                justPressedKeys.remove(e.keyCode)
                                // Если клавишу отпустили - удалить из набора нажатых клавишь
                            }
                        }

                        return false
                        // значит: не блокировать это событие, пусть система его видит
                    }
                }
            )
        isInstalled = true
        // Слушатель уже поставлен и слушает
    }

    fun isDown(keyCode: Int): Boolean {
        // Проверка на то, нажата ли тсейчас конкретная клавиша
        return keyCode in pressedKeys
    }

    fun consumeJustPressed(keyCode: Int): Boolean{
        // Ловим клавишу один раз
        // ЛогикаЖ:
        // если клавиша есть в justPressed, то вернуть true удалить ее оттуда
        return if (keyCode in justPressedKeys){
            justPressedKeys.remove(keyCode)
            true
        } else{
            false
        }
    }
}

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST,
    DOOR
}

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val worldX: Float,
    val worldZ: Float,
    val interactRadius: Float
)

data class ObstacleDef(
    val centerX: Float,
    val centerZ: Float,
    val halfSize: Float
)
// Половина размера объекта удобна для определения столкновения с объектом по осям

data class NpcMemory (
    val hasMet: Boolean,
    val timeTalked: Int,
    val receivedHerb: Boolean,
)

data class PlayerState(
    val playerId: String,

    val worldX: Float,
    val worldZ: Float,

    val yawDeg: Float,
    // Куда смотрит игрок в градусах по оси
    // Проще говоря
    // 0 - смотрит вперед, 90 - вправо, 180 - назад, 270 - влево

    val moveSpeed: Float,

    val questState: QuestState,
    val inventory: Map<String, Int>,
    val gold: Int,

    val alchemistMemory: NpcMemory,

    val chestLooted: Boolean,
    val doorOpened: Boolean,

    val currentFocusId: String?,
    // То на какой объект смотрит игрок для взаемодействия и может быть null если объкта нет

    val hintText: String,
    val pinnedQuestEnabled: Boolean,
    val pinnedTargetId: String?

)
fun herbCount(player: PlayerState): Int{
    return player.inventory["herb"] ?: 0
}

fun lerp(current: Float, target: Float, t: Float): Float{
    // Линейная интерполяция - нужна для плавного перемещения объекта от 1 точки к другой
    return current + (target - current) * t
}
fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx * dx + dz * dz)
}

fun normalizeOrZero(x: Float, z: Float): Pair<Float, Float>{
    // Функция определяет произвольный вектор движения в единичный векто
    // Зачем?
    // Если игрок зажмет W и D одновреммено, то в движении будет сырым (1, -1)
    // и диагональ станет быстрее прямого движения
    // Это ошибка, поэтому нормализуем вектор движения (то есть делаем его длину равной 1)

    val len = sqrt(x*x + z*z)
    // len - длина вектора

    return if (len <= 0.0001f){
        // Если длина почти ноль значит игрок по сути не движется
        // что не получить мусор безопасно возвращаем нулевой вектор
        0f to 0f
        // to это разделитель для создания Pair
    }else{
        (x / len) to (z / len)
        // После данной операции - длина вектора станет равна примерно 1
        // То есть и напрвление сохраненно и скорость по диагонали не будет ломаться
    }
}

fun computeYawDegDirection(dirX: Float, dirZ: Float): Float{
    // Проврка, под каким углом надо смотреть игроку, если он движется в сторону dirX, dirZ
    val raw = Math.toDegrees(atan2(dirX.toDouble(), dirZ.toDouble())).toFloat()
    // atan2 - позволяет зная направление получить угол - это и будет угол куда смотрит игрок
    // Math.toDegrees(...) - это преобразование в градусы
    // atan - возвращает угол в радианах - но нам нужны градусы
    // Double преобразоваем по 2 причинам 1. Болле точные значения, 2. atan2 ожидает именно double в виде параметра
    return if (raw < 0f) raw + 360f else raw
    // Зачем?
    // atan может вурнуть угол в минусовом значении
    // Нам лучше держать градусы в диапозоне от 0 до 360
    // Так что, чтобы преобразовать в порложительное -90 прибаляем к нему полные 360 и он становится 270
}
fun initialPlayerState(playerId: String): PlayerState{
    // Разделение на нескольких игроков

    return if (playerId == "Stas"){
        PlayerState(
            "Stas",
            0f,
            0f,
            0f,
            3.2f,
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
            "Подойди к любой области на карте",
            true,
            "alchemist"
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            0f,
            3.2f,
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
            "Подойди к любой области на карте",
            true,
            "alchemist"
        )
    }
}

fun computePinnedTargetId(player: PlayerState): String? {
    if (!player.pinnedQuestEnabled) return null

    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "alchemist"

        QuestState.WAIT_HERB ->{
            if (herbs < 3) "herb_source" else "alchemist"
        }

        QuestState.GOOD_END ->{
            if (!player.chestLooted) "reward_chest"
            else if (!player.doorOpened) "door"
            else null
        }

        QuestState.EVIL_END -> null
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

    if (player.currentFocusId != "alchemist") {
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

data class CmdMoveAxis(
    override val playerId: String,
    val axisX: Float,
    val axisZ: Float,
    val deltaSec: Float
): GameCommand

data class CmdInteract(
    override val playerId: String
): GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand

data class CmdTogglePinnedQuest(
    override val playerId: String
): GameCommand

sealed interface GameEvent{
    val playerId: String
}

data class PlayerMoved(
    override val playerId: String,
    val newGridX: Float,
    val newGridZ: Float
): GameEvent

data class MovementBlocked(
    override val playerId: String,
    val blockedX: Float,
    val blockedZ: Float
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

data class FocusChanged(
    override val playerId: String,
    val newFocus: String?
): GameEvent

data class PinnedTargetChange(
    override val playerId: String,
    val newTargetId: String?
): GameEvent

class GameServer{
    private val staticObstacles = listOf(
        ObstacleDef(centerX = 0f, centerZ = 1f, halfSize = 0.45f),
        ObstacleDef(centerX = 1f, centerZ = 1f, halfSize = 0.45f),
        ObstacleDef(centerX = 1f, centerZ = 0f, halfSize = 0.45f),
    )

    private val doorObstacle = ObstacleDef(centerX = 0f, centerZ = -3f, halfSize = 0.45f)
    // В закрытом состоянии дверь тоже препятствие

    val worldObject = listOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.3f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "reward_chest",
            WorldObjectType.CHEST,
            0f,
            3f,
            1.3f
        ),
        WorldObjectDef(
            "door",
            WorldObjectType.DOOR,
            0f,
            -3f,
            1.3f
        )
    )

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

    fun getPlayer(playerId: String): PlayerState {
        return _player.value[playerId] ?: initialPlayerState(playerId)
    }

    fun updatePlayer(playerId: String, changed: (PlayerState) -> PlayerState){
        val oldMap = _player.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = changed(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _player.value = newMap.toMap()
    }

    private fun isPointInsideObstacle(x: Float, z: Float, obstacle: ObstacleDef, playerRadius: Float): Boolean{
        // Отвечает на вопрос, если у игрока точка (x, z), то он задел препятствие или нет
        return abs(x - obstacle.centerX) <= (obstacle.halfSize + playerRadius) &&
                abs(z - obstacle.centerZ) <= (obstacle.halfSize + playerRadius)
        // abs(x - obstacle.centerX) - определяет насколько мы далеко от центра препятствия по x
        // obstacle.halfSize + playerRadius - допустимая граница касания
        // Если в итоге и по x и по z он в опасной зоне - значит он задел препятствие
    }

    private fun isBlockedForPlayer(player: PlayerState, x: Float, z: Float): Boolean{
        val playerRadius = 0.22f
        // Толщина игрока

        for (obstacle in staticObstacles){
            if (isPointInsideObstacle(x, z, obstacle, playerRadius)) return  true
        }

        if (!player.doorOpened && isPointInsideObstacle(x, z, doorObstacle, playerRadius)){
            return true
        }

        return false
    }

    private fun isObjectAvailableForPlayer(obj: WorldObjectDef, player: PlayerState): Boolean{
        // Метод проверки, доступен ли тот ил иной обьект сейчас дял взаемодействия с игроком

        return when(obj.type){
            WorldObjectType.ALCHEMIST -> true
            WorldObjectType.HERB_SOURCE -> true

            WorldObjectType.DOOR -> true
            WorldObjectType.CHEST -> player.questState == QuestState.GOOD_END && !player.chestLooted
        }
    }

    private fun isObjectInFrontOfPlayer(player: PlayerState, obj: WorldObjectDef): Boolean{
        // Проверка находится ли сейчас обьект перед игроком

        val yawRad = Math.toRadians(player.yawDeg.toDouble())
        // yawRad - угол взгляда игрока в градусах
        // sin и cos работают в радианах, надо коректировать для работы с ними
        val forwardX = sin(yawRad).toFloat()
        val forwardZ = (-cos(yawRad).toFloat())
        // Можно представить стрелу из груди персонажа, она показывает, куда смотрит игрока
        // forwardX и forwardZ - координаты этой стрелы по плоскости

        val toObjX = obj.worldX - player.worldX
        val toObjZ = obj.worldZ - player.worldZ
        // Это вектор от игрока к объекту, где находится объект относительно игрока

        val dist = max(0.0001f, distance2d(player.worldX, player.worldZ, obj.worldX, obj.worldZ))
        // Расстояние до объекта

        val dirToObjX = toObjZ / dist
        val dirToOdjZ = toObjZ / dist
        // Нормализанное напрвление к объекту
        // то есть в какую сторону он от нас находится без влияния длинны вектора

        val dot = forwardX * dirToObjX + forwardZ * dirToOdjZ
        // dot - скалярное произвидение
        // на простом уровне:
        // Эта цифра отвечает на вопрос: на сколько объект в переди?
        // если объект прямо перед игроком: dot близок к 1
        // Если объект сбоку: dot будет 0
        // Если объект сзади: dot будет отрицательным

        return dot > 0.45f
        // если dot достаточно большой считаем что объект спереди
        // 0.45f - это широкий конус перед игроком
        // Если сделать 0.9 - придется смотреть пости идеально в центр
        // Если сделать 0.1 будет слишком мягко срабатывать
    }

    private fun pickInteractTarget(player: PlayerState): WorldObjectDef?{
        // Выбираем объект для взаемодействия
        val candidates = worldObject.filter { obj ->
            isObjectInFrontOfPlayer(player, obj)
                    && distance2d(player.worldX, player.worldZ, obj.worldX, obj.worldZ) <= obj.interactRadius
                    && isObjectInFrontOfPlayer(player, obj)
        }
        return candidates.minByOrNull { obj ->
            distance2d(player.worldX, player.worldZ, obj.worldX, obj.worldZ)
        }
    }
    private suspend fun refreshDerivedState(playerId: String){
        // Пересчет вторичных состояний игрока
        // втооричные - это те, что игрок не вводит напрямую, они выводятся яиз других данных
        // Например:
        // - focus object
        // - active quest target
        // - hint text

        val player = getPlayer(playerId)
        val target = pickInteractTarget(player)
        val newFocusId = target?.id

        val newPinnedTargetId = computePinnedTargetId(player)

        val newHint =
            when(newFocusId){
                "alchemist" -> "E: Поговорить с алхимоком"
                "herb_source" -> "E: Собрать траву"
                "reward_chest" -> "E: Открыть сундук"
                "door" -> "E: Открыть дверь"
                else -> "WASD / Стрелки - движение, Е - взаемодействие"
            }
        val oldFocusId = player.currentFocusId
        val oldPinnedId = player.pinnedTargetId

        updatePlayer(playerId){ p ->
            p.copy(
                currentFocusId = newFocusId,
                pinnedTargetId = newPinnedTargetId,
                hintText = newHint
            )
            // copy - создает новую копию data class с изменяемыми полями
            // Удобно и что главное - безопасный метод обновления состояния
        }
        if (oldFocusId != newFocusId){
            _events.emit(FocusChanged(playerId, newFocusId))
        }
        if (oldPinnedId != newPinnedTargetId){
            _events.emit(PinnedTargetChange(playerId, newPinnedTargetId))
        }
    }

    private suspend fun progressCommand(cmd: GameCommand){
        when(cmd){
            is CmdMoveAxis -> {
                val player = getPlayer(cmd.playerId)

                val (dirX, dirZ) = normalizeOrZero(cmd.axisX, cmd.axisZ)
                // Возвращает пару значений нормализованых x и z
                if (dirX == 0f && dirZ == 0f){
                    refreshDerivedState(cmd.playerId)
                    return
                }
                val newYaw = computeYawDegDirection(dirX, dirZ)

                val distance = player.moveSpeed * cmd.deltaSec
                // Сколько игрок пройдет дистанции за этот кадр

                val newX = player.worldX + dirX * distance
                val newZ = player.worldZ + dirZ * distance
                // куда игрок хочет пойти в следующем кадре

                val canMoveX = !isBlockedForPlayer(player, newX,player.worldZ)
                val canMoveZ = !isBlockedForPlayer(player, player.worldX, newZ)
                // Двигаем игрока по координатам по отдельности

                var finalX = player.worldX
                var finalZ = player.worldZ

                if (canMoveX) finalX = newX
                if (canMoveZ) finalZ = newZ

                if (!canMoveX && !canMoveZ){
                    _events.emit(MovementBlocked(cmd.playerId, newX, newZ))
                }

                updatePlayer(cmd.playerId){ p ->
                    p.copy(
                        worldX = finalX,
                        worldZ = finalZ,
                        yawDeg = newYaw
                    )
                }

                _events.emit(PlayerMoved(cmd.playerId, finalX, finalZ))
                refreshDerivedState(cmd.playerId)
            }
        }
    }
}