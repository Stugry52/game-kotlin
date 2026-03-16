package QuestJournal2

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

import kotlinx.coroutines.flow.MutableStateFlow           // MutableStateFlow - радиостанция событий (мы туда)
import kotlinx.coroutines.flow.StateFlow                  // StateFlow - только чтение для подписчиков
import kotlinx.coroutines.flow.MutableSharedFlow          // MutableSharedFlow - табло состояний
import kotlinx.coroutines.flow.SharedFlow                 // SharedFlow - только чтение состояния
import kotlinx.coroutines.flow.asSharedFlow               // asSharedFlow() - отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow                // asStateFlow() - отдать наружу только StateFlow
import kotlinx.coroutines.flow.collect

import kotlinx.coroutines.flow.filter                     // фильтрация на какие события, будем реагировать
import kotlinx.coroutines.flow.flatMapLatest              // позволяет переключать потоки (для переключения потоков активного игрока)
import kotlinx.coroutines.flow.map                        // преобразование события в строку (для логирования)
import kotlinx.coroutines.flow.onEach                     // сделать действия для каждого элемента события
import kotlinx.coroutines.flow.launchIn                   // запустить подписку в определенном scope

// ---------- Статусы и маркеры квестов ------- //

enum class QuestStatus{
    ACTIVE,
    COMPLETED
}

enum class QuestMarker{
    NEW,
    PINNED,
    COMPLETED,
    NONE              // Обычный актив
}

// -------- Запись квеста на сервере (то что хранить и обрабатывает сервер)------ //
data class QuestStateOnServer(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val step: Int,
    val progressCurrent: Int,
    val progressTarget: Int,
    val isNew: Boolean,
    val isPinned: Boolean
)

// --------- Запись квеста для UI -------- //

data class QuestJournalEntry(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val objectiveText: String,        // Подсказка, что делать дальше
    val progressText: String,
    val progressBar: String,
    val marker: QuestMarker,
    val markerHint: String
)

sealed interface GameEvent{
    val playerId: String
}

data class ItemCollected(
    override val playerId: String,
    val itemId: String,
    val countAdded: Int
): GameEvent

data class GoldPaid(
    override val playerId: String,
    val amount: Int
): GameEvent

data class  ItemGivenToNpc(
    override val playerId: String,
    val npcId: String,
    val itemId: String,
    val count: Int
): GameEvent

data class QuestJournalUpdated(
    override val playerId: String
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String

): GameEvent

// ---------- Команды клиента к серверу ------ //

sealed interface GameCommand{
    val playerId: String
}

data class CmdOpenQuest(
    override val playerId: String,
    val questId: String
): GameCommand

data class CmdPinned(
    override val playerId: String,
    val questId: String
): GameCommand

data class CmdCollectItem(
    override val playerId: String,
    val itemId: String,
    val count: Int
): GameCommand

data class CmdPayGold(
    override val playerId: String,
    val amount: Int
): GameCommand

data class  CmdGiveItemToNpc(
    override val playerId: String,
    val npcId: String,
    val itemId: String,
    val count: Int
): GameCommand

data class CmdGiveGoldDebug(
    override val playerId: String,
    val amount: Int
): GameCommand

// ------ Информация игрока для синхронизации на сервере --------- //
data class PlayerData(
    val playerId: String,
    val gold: String,
    val inventory: Map<String, Int>
)

class QuestSystem{
    fun objectiveFor(q: QuestStateOnServer): String{
        return when(q.questId){
            "q_alchemist" -> when (q.step){
                0 -> "поговори с алхимиком"
                1 -> "Собери траву: ${q.progressCurrent}/${q.progressTarget}"
                2 -> "Отдай алхимику траву"
                else -> "Квест завершен"
            }
            "q_guard" -> when (q.step){
                0 -> "Поговорить со стражником"
                1 -> "Заплатить стражнику золото: ${q.progressCurrent}/${q.progressTarget}"
                else -> "Проход закрыт"
            }
            else -> "Неизвестный квест"
        }
    }

    fun markHintFor(q: QuestStateOnServer): String{
        return when (q.questId){
            "q_alchemist" -> when (q.step){
                0 -> "NPC: Алхимик"
                1 -> "Сбор Herb"
                2 -> "NPC: Алхимик - Сдать квест"
                else -> "Готово"
            }
            "q_guard" -> when (q.step){
                0 -> "NPC: Стражник"
                1 -> "Передать золото NPC: Стражнику"
                else -> "Готово"
            }
            else -> ""
        }
    }

    fun progressBarText(current: Int, target: Int, blocks: Int = 10): String{
        if (target <= 0) return ""

        val ratio = current.toFloat() / target.toFloat()
        // toFloat() - перевод Int в Float, чтобы деление было дробным

        val filled = (ratio * blocks).toInt().coerceIn(0 , blocks)
        // coerceIn - ограничивает число в пределах от 0 до ... 10 (blocks)

        val empty = blocks -filled

        return "█".repeat(filled) +  "░".repeat(empty)
        // repeat(n) - повторить строку n-раз
    }

    fun markerFor(q: QuestStateOnServer): QuestMarker{
        return when{
            q.status == QuestStatus.COMPLETED -> QuestMarker.COMPLETED
            q.isPinned -> QuestMarker.PINNED
            q.isNew -> QuestMarker.NEW
            else -> QuestMarker.NONE
        }
    }

    fun toJournalEntry(q: QuestStateOnServer): QuestJournalEntry{
        val objective = objectiveFor(q)
        val progressText = if (q.progressTarget > 0) "${q.progressCurrent} / ${q.progressTarget}" else ""
        val bar = if (q.progressTarget > 0) progressBarText(q.progressCurrent, q.progressTarget) else ""

        return QuestJournalEntry(
            q.questId,
            q.title,
            q.status,
            objective,
            progressText,
            bar,
            markerFor(q),
            markHintFor(q)
        )
    }

    fun onEvent(
        playerId: String,
        quests: List<QuestStateOnServer>,
        event: GameEvent,
    ): List<QuestStateOnServer>{
        val copy = quests.toMutableList()

        for (i in copy.indices){
            val q = copy[i]

            if (q.status == QuestStatus.COMPLETED) continue

            if (q.questId == "q_alchemist"){
                val update = updateAlchemistQuest(q, event)
                copy[i] = update
            }

            if (q.questId == "q_alchemist"){
                val update = updateGuardQuest(q, event)
                copy[i] = update
            }
        }
        return copy.toList()
    }

    private fun updateAlchemistQuest(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer{
        // Автоматический переход из step 0 в step 1 (как будто он сразу говорит с нпс)
        // сбор травы - вы меняете progressCurrent по умолчанию 0 и симулируете поднятие изменяя до progressTarget
        // Создаете передачу предметов нпс если условие удовлетворяют
    }
}






















