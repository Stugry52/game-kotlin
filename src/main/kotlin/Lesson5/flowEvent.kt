package Lesson5

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

import kotlinx.coroutines.launch                          // launch {  } - запускаем корутину
import kotlinx.coroutines.flow.MutableStateFlow           // MutableStateFlow - радиостанция событий (мы туда)
import kotlinx.coroutines.flow.StateFlow                  // StateFlow - только чтение для подписчиков
import kotlinx.coroutines.flow.MutableSharedFlow          // MutableSharedFlow - табло состояний
import kotlinx.coroutines.flow.SharedFlow                 // SharedFlow - только чтение состояния
import kotlinx.coroutines.flow.asSharedFlow               // asSharedFlow() - отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow                // asStateFlow() - отдать наружу только StateFlow
import kotlinx.coroutines.flow.collect                    // collect {  }- слушать поток

import kotlinx.serialization.Serializable                   // Serializer
import kotlinx.serialization.encodeToString               // encodeToString
import kotlinx.serialization.decodeFromString             // decodeFromString
import kotlinx.serialization.json.Json                    // Json - формат Json
import lesson3.Player

import java.io.File
import java.lang.Exception
import java.time.temporal.TemporalAmount

sealed interface GameEvent{
    val playerId: String
}

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
): GameEvent

data class PlayerProgressSystem(
    override val playerId: String,
    val reason: String
): GameEvent

// --------- Серверные данные игрока (те данные которые мы будем сохранять в JSON файл)
// @Serializable - аннотация или пометка или же указатель на то, что класс под данной аннотацией, можно сериализовать
@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val questState: Map<String, String>
)

// ------------ ServerWorld с Flow -------- //
// Вместо EventBus теперь используем SharedFlow<GameEvent> (рассыльщик событий)
// Вместо states - StateFlow - для хранения в себе актуального состояния игрока
// Это горячие потоки которые всегда выполняются в корутине

class ServerWorld(
    initialPlayer: String
){
    private val _events = MutableSharedFlow<GameEvent>(replay = 0)
    // MutableSharedFlow - изменяемый рассыльщик событий, мы можем отправлять событие внутрь потока
    // replay - означает "не присылать старые события, новым слушателям"
    // События это почти всегда "что случилось сейчас" они не должны повторяться для новых слушателей

    val event: SharedFlow<GameEvent> = _events.asSharedFlow()
    // asSharedFlow - "получить версию для чтения", через нее публиковать события нельзя, только слушать

    private val _playerState = MutableStateFlow(
        PlayerSave(
            initialPlayer,
            100,
            0,
            mapOf("q_training" to "START")
        )
    )

    val playerState: StateFlow<PlayerSave> = _playerState.asStateFlow()

    // ------ Команды общения клиента с сервером ----------//

    fun dealDamage(playerId: String, targetId: String, amount: Int){
        val old = _playerState.value

        val newHp = (old.hp - amount).coerceAtLeast(0)

        _playerState.value =old.copy(hp = newHp)
        // copy - data class функция - создает копию объекта с измененным полем
    }

    fun questStateChanged(playerId: String, questId: String, newState: String){
        val old = _playerState.value

        // + создает новую Map (старую Мар не ломаем)
        val newQuestState = old.questState + (questId to newState)

        _playerState.value = old.copy(questState = newQuestState)
    }

    // suspend функция, которая может ждать (delay/emit)
    suspend fun emitEvent(event: GameEvent){
        _events.emit(event)
        // emit() - отправить событие всем слушателям
        // emit может подождать если подписчики медленные (это нормально при управлении потоком)
    }
}

//------- Сериализация объекта в строку Json --------//
class SaveSystem{
    private val json = Json{
        prettyPrint= true
        encodeDefaults = true
    }
    // prettyPrint - делает json красивым и читаемым
    // encodeDefaults - записывает значение по умолчанию тоже

    private fun saveFile(playerId: String): File{
        val dir = File("saves")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$playerId.json")
    }

    fun save(player: PlayerSave){
        val text = json.encodeToString(player)
        // encodeToString - превращает наш код в строку json файла

        saveFile(player.playerId).writeText(text)
    }

    fun load(playerId: String): PlayerSave? {
        val file = saveFile(playerId)
        if (!file.exists()) return null

        val text = file.readText()

        return try {
            json.decodeFromString<PlayerSave>(text)
            // Преобразование текста из строки JSON в объект PlayerSave
        }catch (e: Exception){
            return null
        }
    }
}

class UiState{
    // Создать состояние хранящее активного игрока - по умолчанию Олег
    // тоже самое для hp, gold
    // Создать состояние хранящее questState - изменяемым состоянием по умолчанию "START"

    // Состояние logLines изменяемое - хранящая типы данных Список только со строками -> по умолчанию пустой список
}