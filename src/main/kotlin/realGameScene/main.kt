package realGameScene

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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.selects.select
import org.w3c.dom.Text

import kotlin.math.sqrt

// ======== Типа объектов игрового мира ======= //

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    BAD_END
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST
}

// Описание объекта в мире
data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float
)

// Память Npc - о конкретном игроке (их прогресс квеста)
data class NpcMemory(
    val hasMet: Boolean,        // Встретился ли игрок уже с Npc
    val timeTalked: Int,        // Сколько раз поговорил
    val receivedHerb: Boolean,   // Отдали ли уже траву
    val sawPlayerNearSource: Boolean
)

// Состояние игрока на сервере
data class PlayerState(
    val playerId: String,
    val posX: Float,
    val posZ: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val gold: Int,
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,           // В какой локации находится (может быть null - если ни в каком)
    val hintText: String
)

// ======== Основные функции ======= //
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
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            0,
            NpcMemory(
                true,
                2,
                false,
                true
            ),
            null,
            "Подойди к любой области на карте"
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            0,
            NpcMemory(
                false,
                0,
                false,
                false
            ),
            null,
            "Подойди к любой области на карте"
        )
    }
}

// ========== Диалоговая модель для Hud =========== //

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView{
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
            val beOnSource = if (memory.sawPlayerNearSource){
                "Вижу, ты хотя бы дошёл до места, где растёт трава, ты ее принес."
            }else{
                "Не знаю где ты взял эту траву, но пускай будет."
            }
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

        QuestState.BAD_END -> {
            DialogueView(
                "Алхимик",
                "Я не хочу с тобой больше разговаривать, уходи!",
                emptyList()
            )
        }
    }
}


// ======= Команды Клиента к серверу ===== //

sealed interface GameCommand{
    val playerId: String
}

data class CmdMovePlayer(
    override val playerId: String,
    val dx: Float,
    val dz: Float
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

data class CmdSwitchActivePlayer(
    override val playerId: String,
    val newPlayerId: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand


// ====== События сервер к клиенту ========= //

sealed interface GameEvent{
    val playerId: String
}

data class EnteredArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class LeftArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
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

// ======= Серверная логика мира ====== //

class GameServer{
    // Список объектов мира
    val worldObjects = listOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "chest",
            WorldObjectType.CHEST,
            0f,
            3f,
            1.7f
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

    fun getPlayerData(playerId: String): PlayerState{
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

    // поиск объекта ближайшего, в чью зону, попадет игрок
    private fun nearestObject(player: PlayerState): WorldObjectDef?{
        val candidates = worldObjects.filter { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z)
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
            if (herbs < 4)"Собери 4 травы $herbs/4"
            else "У тебя достаточно травы вернись к Хайзенбергу"
        }
        QuestState.GOOD_END -> "Квест завершен на хорошую концовку"
        QuestState.BAD_END -> "Квест завершен на плохую концовку"
    }
}

fun currentZoneText(player: PlayerState): String{
    return when(player.currentAreaId){
        "alchemist" -> "Локация: Хайзенберг"
        "herb_source" -> "Локация: Лаборатория травы"
        "chest" -> "Сундук с сокровищем"
        else -> "Где я"
    }
}

fun formatMemory(memory: NpcMemory): String{
    return "hasMet=${memory.hasMet}, talks=${memory.timeTalked}, receivedHerb=${memory.receivedHerb}"
}

fun eventToText(e: GameEvent): String{
    return when(e){
        is EnteredArea -> "EnteredArea ${e.areaId}"
        is LeftArea -> "LeftArea ${e.areaId}"
        is InteractedWithNpc -> "InteractedWithNpc ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} to ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged ${e.memory}"
        is ServerMessage -> "Server ${e.text}"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        val playerNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        val alchemistNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        val chestNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        chestNode.isVisible = true
        chestNode.transform.translate(0f, 0f, -4f)

        chestNode.onUpdate{
            transform.rotate(35f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }

        alchemistNode.transform.translate(-3f, 0f, 0f)

        val herbNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        herbNode.transform.translate(3f, 0f, 0f)

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }
        server.start(coroutineScope)

        // Будем хранить последние положение игрока в пространстве для отрисовки
        // И смещать игрока сдвигать куб на разницу между прошлой новой точки
        var lastRenderedX = 0f
        var lastRenderedZ = 0f

        playerNode.onUpdate{
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerData(activeId)

            val dx = player.posX - lastRenderedX
            val dz = player.posZ - lastRenderedZ

            playerNode.transform.translate(dx, 0f, dz)

            lastRenderedX = player.posX
            lastRenderedZ = player.posZ


        }
        alchemistNode.onUpdate{
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        herbNode.onUpdate{
            transform.rotate(35f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }

    }
    addScene {
        setupUiScene(ClearColorLoad)

        hud.activePlayerIdFlow.flatMapLatest { pid ->
            server.players.map { map ->
                map[pid] ?: initialPlayerState(pid)
            }
        }
            .onEach { player ->
                hud.playerSnapShot.value = player
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

                Text("Игрок ${hud.activePlayerIdUi.use()}"){modifier.margin(bottom = sizes.gap)}

                Text("Позиция: x=${"%.1f".format(player.posX)} z = ${"%.1f".format(player.posZ)}"){}
                Text(currentZoneText(player)){modifier.font(sizes.smallText)}

                Text("Золото: ") {  }

                Text("QuestState: ${player.questState}"){modifier.font(sizes.smallText)}
                Text(currentObjective(player)){modifier.margin(bottom = sizes.gap)}
                Text(formatInventory(player)) {modifier.font(sizes.smallText)}
                Text("Hint: ${player.hintText}"){}

                Row {
                    Button("Лево") {
                        modifier.onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = -0.5f, dz = 0f))
                        }
                    }
                    Button("Право") {
                        modifier.onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = 0.5f, dz = 0f))
                        }
                    }
                    Button("Вперед") {
                        modifier.onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = 0f, dz = -0.5f))
                        }
                    }
                    Button("Назад") {
                        modifier.onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = 0f, dz = 0.5f))
                        }
                    }
                }
                    // Кнопка взаимодействия с ближайшим
                    // Отображение текста диалога и кнопок выбора
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
                                            CmdCooseDialogueOption(player.playerId, option.id)
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
    }
}


// 1.1 a) Люблю котлин
// 1.2 a) Для рассылки событий
// 1.3 b) до 18340














