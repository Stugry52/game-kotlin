package Lesson6

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene

import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.*

import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time

import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.modules.ui2.UiModifier.*
import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import lesson2.HEALING_POTION
import lesson2.WOOD_SWORD
import lesson2.putIntoSlot

// В настоящий игре много событий и процессов заточенных на времени
// Яд тикает раз в секунду
// кулдаун способности 1.5 секунд
// задержка сети 200 мс
// квест открывает дверь через 10 секунд
// и тдтдтд

// Если все это будет лежать в он onUpdate и таймеры будут обновляться вручную - это быстро превратится в кашу

// Корутины это решают
// Позволяют писать время как обычно код: подождал -> выполнил действие -> подождал -> выполнил действие
// не замораживают всю игру и UI
// удобно отменяются или прерываются (например если яд уже был наложен на игрока - отменить корутину и запустить новую с обновленным эффектом яда)

// Основные команды корутины
// launch {...} - запуск корутины (включение процесса или запуск таймера)
// delay (ms) - приостанавливает на ограниченное число миллисекунд корутины, но не останавливает ее
// Job + cancel()
// Job - контролер управления корутиной
// cancel() - отмена / остановка корутины (например снять эффект яда)

// Delay работает только внутри launch
// потому что delay это suspend функция
// suspend fun - функция которая может приостанавливаться (ждать)
// обычная функция на это не способна
// suspend функция можно вызвать только внутри корутины (launch) или внутри другой suspend функции

// Использование scene.coroutineScope
// В Kool есть свою корутинную область - почему это удобно:
// Когда сцена закрывается - корутины этой сцены тоже автоматически прерываются
// это просто безопаснее чем глобальные корутины

// -------- GameState - состояние игрока и UI ------ //
class GameState{
    val playerId = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)
    val maxHp = 100

    val poisonTickLeft = mutableStateOf(0)
    val regenTicksLeft = mutableStateOf(0)

    val attackCooldownsLeft = mutableStateOf(0L)

    val logLines = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(game: GameState, text: String){
    game.logLines.value = (game.logLines.value + text).takeLast(20)
}

// ----- EffectManager ----- //

class EffectManager(
    private val game: GameState,
    private val scope: kotlinx.coroutines.CoroutineScope
    // Передаем сюда область корутин чтобы при выполнении она была привязана к сцене
){
    private var regenJob: Job? = null
    // Job - это задача корутина, которой мы сможем управлять
    // regenJob - это ссылка на корутину чтобы мы могли к ней обращаться и управлять ей
    // null по умолчанию, потому что корутина по умолчанию не привязана к ссылке (не запущена)

    private var poisonJob: Job? = null

    fun applyPoison(ticks: Int, damagePerTicks: Int, intervalMs: Long){
        // Метод наложения яда на игрока
        // Если яд был наложен - отменяем прошлую корутину
        poisonJob?.cancel()
        // ?. - Безопасный вызов, значит, если poisonJob окажется null - то cansel не выполнится

        game.poisonTickLeft.value += ticks
        // Обновляем счетчик тиков сколько будет действовать эффект яда

        pushLog(game, "Яд применен на ${game.playerId} длительность ${game.poisonTickLeft}")

        // Запуск новой корутины действия эффекта яда
        poisonJob = scope.launch {
            while (isActive && game.poisonTickLeft.value > 0){
                // isActive - корутина ещё существует? Не была ли отменена
                delay(intervalMs)
                // Пауза между нанесением урона от действия эффекта яда

                game.poisonTickLeft.value -= 1

                game.hp.value -= (game.hp.value - damagePerTicks).coerceAtLeast(0)
                // coerceAtLeast - округлит до 0, даже если число hp упадет ниже 0
                pushLog(game, "Тик яда: -$damagePerTicks, Осталось Hp: ${game.hp.value}")
            }
            pushLog(game, "Эффект яда завершен")
        }
    }

    fun applyRegen(ticks: Int, healPerTicks: Int, intervalMs: Long){
        regenJob?.cancel()

        game.regenTicksLeft.value += ticks
        pushLog(game, "Реген применен на ${game.playerId}, длительность ${game.regenTicksLeft} тиков")

        regenJob = scope.launch {
            while (isActive && game.regenTicksLeft.value > 0){
                delay(intervalMs)

                game.regenTicksLeft.value -= 1
                game.hp.value = (game.hp.value + healPerTicks).coerceAtLeast(game.maxHp)
                pushLog(game, "Тик регена: +$healPerTicks, Осталось Hp: ${game.hp.value}")
            }
            pushLog(game, "Эффект регена завершен")
        }
    }

    fun cancelPoison(){
        poisonJob?.cancel()
        poisonJob = null
        game.poisonTickLeft.value = 0
        pushLog(game, "Яд снят")
    }

    fun cancelRegen(){
        regenJob?.cancel()
        poisonJob = null
        game.regenTicksLeft.value = 0
        pushLog(game, "реген снят")
    }
}

class CooldownManager(
    private val game: GameState,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private var cooldownJob: Job? = null

    fun startAttackCooldown(totalMs: Long){
        cooldownJob?.cancel()

        game.attackCooldownsLeft.value = totalMs
        pushLog(game, "Кулдаун атаки $totalMs мс")

        cooldownJob = scope.launch {
            val  step = 100L

            while (isActive && game.attackCooldownsLeft.value > 0L){
                delay(step)
                game.attackCooldownsLeft.value = (game.attackCooldownsLeft.value - step)
            }
        }
    }

    fun canAttack(): Boolean{
        return game.attackCooldownsLeft.value <= 0L
    }
}

fun main() = KoolApplication{
    val game = GameState()

   // val cooldownManager = CooldownManager(game, )
    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate {
                cube{ colored()}
            }
            shader = KslPbrShader{
                color { vertexColor() }
                metallic(1f)
                roughness(0.8f)
            }
            onUpdate{
                transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f,-1f,-1f))
            setColor(Color.WHITE, 7f)
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)
       // val effectManager = EffectManager(game, CoroutineScope())

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f,0f,0f, 0.5f), 14.dp))
                .padding(12.dp)

            Column {
                Text("Игрок: ${game.playerId.use()}"){}
                Text("Hp: ${game.hp.use()}"){}

                Row {
                    modifier.margin(top = 6.dp)

                    Button("Получить зелье") {
                        modifier
                            .margin(end = 8.dp)
                            .onClick{

                            }
                    }
                    Button("Деревянный меч") {
                        modifier
                            .margin(end = 8.dp)
                            .onClick{

                            }
                    }
                }
            }
        }

    }

}























