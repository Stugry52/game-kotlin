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
import kotlin.collections.plus

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
    val newWorldX: Float,
    val newWorldZ: Float
): GameEvent

data class MovementBlocked(
    override val playerId: String,
    val blockedWorldX: Float,
    val blockedWorldZ: Float
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
            is CmdInteract -> {
                val p = getPlayer(cmd.playerId)
                val target = pickInteractTarget(p)

                if (target == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов"))
                    return
                }

                when(target.type){
                    WorldObjectType.ALCHEMIST ->{
                        val oldMemory = p.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timeTalked = oldMemory.timeTalked + 1
                        )

                        updatePlayer(cmd.playerId){p ->
                            p.copy(alchemistMemory = newMemory)
                        }
                        _events.emit(InteractedWithNpc(cmd.playerId, target.id))
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

                        _events.emit(InteractedWithHerbSource(cmd.playerId, target.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }

                    WorldObjectType.CHEST ->{
                        if (p.questState != QuestState.GOOD_END){
                            _events.emit(ServerMessage(cmd.playerId, "Сундук пока что закрыт"))
                            return
                        }

                        if (p.chestLooted){
                            _events.emit(ServerMessage(cmd.playerId, "Сундук уже открыт"))
                            return
                        }

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(
                                gold = p.gold + 20,
                                chestLooted = true
                            )
                        }

                        _events.emit(InteractedWithChest(cmd.playerId, target.id))
                        _events.emit(ServerMessage(cmd.playerId, "Ты открыл сундук и получил 20 золота"))
                        refreshDerivedState(cmd.playerId)
                    }

                    WorldObjectType.DOOR ->{
                        if (p.questState != QuestState.GOOD_END){
                            _events.emit(ServerMessage(cmd.playerId, "Дверь пока что закрыта"))
                            return
                        }

                        if (p.doorOpened){
                            _events.emit(ServerMessage(cmd.playerId, "Дверь уже открыта"))
                            return
                        }

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(
                                doorOpened = true
                            )
                        }

                        _events.emit(InteractedWithDoor(cmd.playerId, target.id))
                        _events.emit(ServerMessage(cmd.playerId, "Ты открыл дверь"))
                        refreshDerivedState(cmd.playerId)
                    }
                }
            }

            is CmdChooseDialogueOption -> {
                val p = getPlayer(cmd.playerId)

                if (p.currentFocusId != "alchemist"){
                    _events.emit(ServerMessage(cmd.playerId, "Сначала подойди к Алхимику"))
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
                            p.copy(questState = QuestState.EVIL_END)
                        }
                    }
                    "give_herb" -> {
                        if (p.questState != QuestState.WAIT_HERB) return

                        val herbs = herbCount(p)

                        if (herbs < 3){
                            return
                        }

                        val newCount = herbs - 3
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

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { _ -> initialPlayerState(cmd.playerId) }
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен до заводских настроек"))
                refreshDerivedState(cmd.playerId)
            }

            is CmdTogglePinnedQuest -> {
                updatePlayer(cmd.playerId){p ->
                    p.copy(
                        pinnedQuestEnabled = !p.pinnedQuestEnabled
                    )
                }

                val after = getPlayer(cmd.playerId)
                _events.emit(ServerMessage(cmd.playerId, "Pinned marker = ${after.pinnedQuestEnabled}"))
                refreshDerivedState(cmd.playerId)
            }
        }
    }
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")

    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player: PlayerState): String{
    return if (player.inventory.isEmpty()){
        "Инвентарь: пуст"
    }else{
        "Инвентарь: " + player.inventory.entries.joinToString { "${it.key} x${it.value}"}
    }
}

fun currentObjective(player: PlayerState): String{
    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "Подойди к алхимику"
        QuestState.WAIT_HERB -> {
            if (herbs < 3)"Собери 3 травы $herbs/3"
            else "У тебя достаточно травы вернись к Хайзенбергу"
        }
        QuestState.GOOD_END -> "Квест завершен на хорошую концовку"
        QuestState.EVIL_END -> "Квест завершен на плохую концовку"
    }
}

fun formatMemory(memory: NpcMemory): String{
    return "hasMet=${memory.hasMet}, talks=${memory.timeTalked}, receivedHerb=${memory.receivedHerb}"
}

fun eventToText(e: GameEvent): String{
    return when(e){
        is PlayerMoved -> "PlayerMoved x=${"%.2f".format(e.newWorldX)}, z=${"%.2f".format(e.newWorldZ)})"
        is MovementBlocked -> "MovementBlocked x=${"%.2f".format(e.blockedWorldX)}, z=${"%.2f".format(e.blockedWorldZ)})"
        is FocusChanged -> "FocusChanged ${e.newFocus}"
        is PinnedTargetChange -> "PinnedTargetChange ${e.newTargetId}"
        is InteractedWithChest -> "InteractedWithChest ${e.chestId}"
        is InteractedWithDoor -> "InteractedWithDoor ${e.doorId}"
        is InteractedWithNpc -> "InteractedWithNpc ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} to ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged ${e.memory}, Сколько раз поговорил = ${e.memory.timeTalked}, Отдал траву = ${e.memory.receivedHerb}"
        is ServerMessage -> "Server ${e.text}"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene{
        defaultOrbitCamera()

        for(x in -5..5){
            for (z in -4..4){
                addColorMesh {
                    generate { cube { colored() } }

                    shader = KslPbrShader{
                        color { vertexColor() }
                        metallic(0f)
                        roughness(0.35f)
                    }
                }.transform.translate(x.toFloat(), -1.2f, z.toFloat())
            }
        }

        val wallCells = listOf(
            ObstacleDef(0f, 1f, 0.45f),
            ObstacleDef(1f, 1f, 0.45f),
            ObstacleDef(1f, 0f, 0.45f),
            ObstacleDef(0f, -3f, 0.45f)
        )

        for (cell in wallCells){
            addColorMesh {
                generate { cube { colored() } }

                shader = KslPbrShader{
                    color { vertexColor() }
                    metallic(0f)
                    roughness(0.35f)
                }
            }.transform.translate(cell.centerX.toFloat(), 0f, cell.centerZ.toFloat())
        }

        val playerNode = addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0f)
                roughness(0.15f)
            }
        }

        val alchemistNode = addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0f)
                roughness(0.15f)
            }
        }
        alchemistNode.transform.translate(-3f,0f,0f)

        val herbNode = addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0f)
                roughness(0.15f)
            }
        }
        herbNode.transform.translate(3f,0f,0f)

        val chestNode = addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0f)
                roughness(0.15f)
            }
        }
        chestNode.transform.translate(1000f,0f,1000f)

        val doorNode = addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0f)
                roughness(0.15f)
            }
        }
        doorNode.transform.translate(0f,0f,-3f)

        server.start(coroutineScope)
    }

    addScene {
        setupUiScene(ClearColorLoad)

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.players.map { map ->
                    map[pid] ?: initialPlayerState(pid)
                }
            }
            .onEach { player ->
                hud.playerSnapShot.value = player
            }
            .launchIn(coroutineScope)

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.event.filter { it.playerId == pid }
            }
            .map { event ->
                eventToText(event)
            }
            .onEach { line ->
                hudLog(hud, "[${hud.activePlayerIdUi.value}] $line")
            }
            .launchIn(coroutineScope)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.5f), 14.dp))
                .padding(12.dp)

            Column {
                val player = hud.playerSnapShot.use()
                val dialogue = buildAlchemistDialogue(player)

                Text("Игрок: ${hud.activePlayerIdUi.use()}"){
                    modifier.margin(bottom = sizes.gap)
                }
                Text("Позиция игрока: ${player.worldX} | ${player.worldZ}"){}
                Text("Взгляд: ${player.yawDeg}"){ modifier.font(sizes.smallText)}

                Text("Квест: ${player.questState}") {  }
                Text(currentObjective(player)) {modifier.font(sizes.smallText)}
                Text(formatInventory(player)) { modifier.font(sizes.smallText) }
                Text("Золото: ${player.gold}") { modifier.font(sizes.smallText) }
                Text("Сундук залутан?: ${player.chestLooted} ") { modifier.font(sizes.smallText) }
                Text("Дверь открыта?: ${player.doorOpened}") { modifier.font(sizes.smallText) }
                Text("Память Npc: ${formatMemory(player.alchemistMemory)}") {
                    modifier
                        .font(sizes.smallText)
                        .margin(bottom = sizes.gap)
                }

                Row{
                    Button("Сменить игрока") {
                        modifier.margin(end = 8.dp).onClick{
                            val newId = if (hud.activePlayerIdUi.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerIdUi.value = newId
                            hud.activePlayerIdFlow.value = newId
                        }
                    }
                }
                Row {
                    Button("Лево") {
                        modifier.onClick {
                            server.trySend(CmdMoveAxis(player.playerId,  -1f, 0f, 1f))
                        }
                    }
                    Button("Право") {
                        modifier.onClick {
                            server.trySend(CmdMoveAxis(player.playerId, 1f, 0f, 1f))
                        }
                    }
                    Button("Вперед") {
                        modifier.onClick {
                            server.trySend(CmdMoveAxis(player.playerId, 0f, -1f, 1f))
                        }
                    }
                    Button("Назад") {
                        modifier.onClick {
                            server.trySend(CmdMoveAxis(player.playerId, 0f, 1f, 1f))
                        }
                    }
                }
                Text("Потрогать:") {
                    modifier.margin(top = sizes.gap)
                }

                Button("Взаимодействие с ближайшим") {
                    modifier.margin(end = 8.dp).onClick{
                        server.trySend(CmdInteract(player.playerId))
                    }
                }
                Text("${dialogue.npcName}:"){
                    modifier.margin(top = sizes.gap)
                }

                Text(dialogue.text){
                    modifier.margin(bottom = sizes.smallGap)
                }

                if (dialogue.options.isEmpty()){
                    Text("(Сейчас доступных ответов нет)"){
                        modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                    }
                }else {
                    Row {
                        for (option in dialogue.options){
                            Button(option.text) {
                                modifier.margin(end = 8.dp).onClick{
                                    server.trySend(
                                        CmdChooseDialogueOption(player.playerId, option.id)
                                    )
                                }
                            }
                        }
                    }
                }

                Text("Log:") {
                    modifier.margin(top = sizes.gap)
                }

                for (line in hud.log.use()){
                    Text(line){
                        modifier.font(sizes.smallText)
                    }
                }
            }
        }
        addPanelSurface {
            
        }
    }
}