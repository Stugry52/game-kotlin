package PlayerCameraFollow

import PlayerKeyboardMovement.QuestState
import PlayerKeyboardMovement.distance2d
import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.math.randomI
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.modules.ui2.AlignmentX
import de.fabmax.kool.modules.ui2.AlignmentY
import de.fabmax.kool.modules.ui2.Box
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
import de.fabmax.kool.modules.ui2.size
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

import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import kotlin.collections.filter
import kotlin.collections.minByOrNull
import kotlin.collections.plus
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class QuestStage{
    NOT_STARTED,
    TALK_TO_NPC,
    CHEST_OPENED
}

enum class WorldObjectType{
    NPC,
    CHEST
}

data class WorldObject(
    val id: String,
    val label: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float
)

class GameState{
    val playerX = mutableStateOf(0f)
    val playerZ = mutableStateOf(0f)
    val playerYawDeg = mutableStateOf(180f)

    val moveSpeed = mutableStateOf(3.5f)

    val questState = mutableStateOf(QuestStage.NOT_STARTED)
    val chestOpened = mutableStateOf(false)
    val gold = mutableStateOf(0)

    val focusObjectId = mutableStateOf<String?>(null)
    val hintText = mutableStateOf("WASD - движение | E - взаемодействие")
    val dialogueText = mutableStateOf("Подойди к фиолетовому кубу-НПС и нажми Е")
    val logLines = mutableStateOf("Сцена загружена")
}

fun pushLog(game: GameState, text: String){
    game.logLines.value = (game.logLines.value + text).takeLast(10)
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx * dx + dz * dz)
}

fun lerp(current: Float, target: Float, t: Float): Float{
    // Линейная интерполяция - нужна для плавного перемещения объекта от 1 точки к другой
    return current + (target - current) * t
}

fun normalizeOrZero(x: Float, z: Float): Pair<Float, Float>{
    val len = sqrt(x * x + z * z)
    return if (len <= 0.0001f) 0f to 0f else (x / len) to (z / len)
}

fun normalizeAngleDeg(angle: Float): Float{
    // Приводит угол к диапозону значений от 0 до 360 (положительные градусы)
    // Пример:
    // -20 -> 340
    // 370 -> 10
    var result = angle
    // абочая копия угла
    while (result < 0f) result += 360f
    // если угол отрицательный - поднимем его вверх
    // пока он не попадет в диапозон 0..360

    while (result >= 360f) result -= 360f
    // Если угол слишком большой, уменьшить его
    return result
}

fun shortestAngleDeltaDeg(from: Float, to: Float): Float{
    // Короткая разница между углами
    // Зачем нужно?
    // Камера должна доворачиватся к игроку самым Коротким путем
    // а не пытаться крутится почти полный круг
    // Например:
    // from = 350    to = 10
    // Понятная разница: 10 - 350 = -340(но это кривой не короткий путь)
    // короткий путь это +20 градусов

    var delta = normalizeAngleDeg(to) - normalizeAngleDeg(from)
    // Сначала считаем обычную разницу между нормализованными углами

    if (delta > 180f) delta -= 360f
        // Если разница слишком большая в плюс -> значит короче повернуть в другую сторону (в минус)
    if (delta < -180f) delta += 360f
        // Если разница слишком большая в минус -> значит короче повернуть в другую сторону (в плюс)
    return delta
}

fun computeYawFromDirection(dirX: Float, dirZ: Float): Float{
    // Если игрок движется в направлении dirX и dirZ - то каким углом он должен смотреть

    val raw = Math.toDegrees(atan2(dirX.toDouble(), (-dirZ.toDouble()))).toFloat()
    // atan2 - функция, которая из напрвления делает угол
    // -dirZ - пишем минус, потому что на сцене по умолчанию у нас движение вперед это -Z
    // Math.toDegrees - переведет подсчитаное в градусы

    return if (raw < 0f) raw + 360f else raw
    // atan2 может вернуть отрицательный угол
    // Для удобства - лучше хранить угл в диапозоне 0..360
    // Поэтому -90 станет 270
}

fun isObjectFrontOfPlayer(
    playerX: Float,
    playerZ: Float,
    playerYawDeg: Float,
    obj: WorldObject
): Boolean{
    // Проверка, находится ли объект перед игроком
    val yawRad = Math.toRadians(playerYawDeg.toDouble())
    // угол взгляда игрока в радианах
    // Нужно, потому что sin/cos - работают на радианах

    val forwardX = sin(yawRad).toFloat()
    val forwardZ = (-cos(yawRad).toFloat())

    // На сколько объект смещен от игрока по x до z
    val toObjX = obj.x - playerX
    val toObjZ = obj.z - playerZ

    val dist = distance2d(playerX, playerZ, obj.x, obj.z)

    // Если объект почти совпал с игроком, считаем его впереди
    if (dist <= 0.0001f) return  true

    val dirToObjectX = toObjX / dist
    val dirToObjectZ = toObjZ / dist

    val dot = forwardX * dirToObjectX + forwardZ * dirToObjectZ
    // Скалярное произвидение
    // Оно показыает, насколько объект совпадает с направлением взгяда игрока
    // dot ~ 1   ->  почти перед игроком
    // dot ~ 0   ->  сбоку
    // dot < 0   ->  сзади

    return dot > 0.45f
    // Если dot достаточно большой, считаем объект впереди
    // 0.45f - это широта конуса которым мы смотрим
}

fun findFocusedObject(game: GameState, objects: List<WorldObject>): WorldObject?{
    // Ищет объект который прямо перед игроком
    // и при этом, который доступен для взаемодействия
    val playerX = game.playerX.value
    val playerZ = game.playerZ.value

    val playerYaw = game.playerYawDeg.value
    // Текущий угол взгляд игрока

    val candidates = objects.filter { obj ->
        distance2d(playerX, playerZ, obj.x, obj.z) <= obj.interactRadius &&
                isObjectFrontOfPlayer(playerX, playerZ, playerYaw, obj)
    }
    // Фильтруем все объекты и оставляем только те, которые рядом с игроком и которые перед игроком

    return candidates.minByOrNull { obj ->
        distance2d(playerX, playerZ, obj.x, obj.z)
    }
    // Если таких объектов найдется несколько - то берем ближайший к игроку
}

fun handleInteract(game: GameState, focused: WorldObject?){
    //
    //

    if (focused == null){
        game.dialogueText.value = "Перед игроком нет объектов для взаимодействия"
        pushLog(game, "Нажми Е, но рядом нет объекта")
        return
    }

    when(focused.type){

        WorldObjectType.NPC -> {
            when(game.questState.value){
                QuestStage.NOT_STARTED -> {
                    game.questState.value = QuestStage.TALK_TO_NPC
                    // Меняем стадию квеста, что игрок уже поговорил

                    game.dialogueText.value =
                        "[Алхимик]: В сундуке лежит награда. Подойди к сундуку, и открой его, там точно не мимик"
                    pushLog(game, "NPC выдал задачу: открыть сундук")
                }

                QuestStage.TALK_TO_NPC -> {

                    var timesToTalk = 0

                    val randomTextNumber = randomI(1, 3)

                    if (!game.chestOpened.value){
                        timesToTalk++
                        when(timesToTalk){
                            0 -> { game.dialogueText.value = "[Алхимик]: сначала открой сундук"

                            }
                            1 -> {game.dialogueText.value = "[Алхимик]: давай открой же его"

                            }
                            2 -> {game.dialogueText.value = "[Алхимик]: та не со мной говори, а открывай сундук"

                            }

                        }

                        pushLog(game, "NPC напомнил про сундук")
                    } else{
                        game.dialogueText.value =
                            "[Алхимик]: Победа, ты справился и получил награду, теперь иди отсюда"
                        pushLog(game, "NPC подтвердил выполнение квеста")
                    }
                }
                QuestStage.CHEST_OPENED -> {
                    game.dialogueText.value =
                        "[Алхимик]: Все уже готово, ты уже взял награду"
                }
            }
        }

        WorldObjectType.CHEST -> {
            var changeToOpenChest = 0
            if (game.questState.value == QuestStage.NOT_STARTED){
                changeToOpenChest++
                if (changeToOpenChest == 50){
                    game.dialogueText.value = "Ты умудрился его отрыть сломав замок"
                    pushLog(game, "Сундук открыт")
                    return
                }
                game.dialogueText.value = "Сундук закрыт, возьми ключ у Алхимика"
                pushLog(game, "Попытка открыть сундук, без ключа")
                return
            }
            if (game.chestOpened.value){
                game.dialogueText.value = "Сундук уже открыт"
                pushLog(game, "Попытка открыть уже открытый сундук")
                return
            }

            game.chestOpened.value = true

            game.questState.value = QuestStage.CHEST_OPENED

            game.gold.value += 20

            game.dialogueText.value = "Ты открыл сундук и получил 20 золота"
            pushLog(game, "Сундук открыт, выдана награда в 20 золота")
        }
    }
}