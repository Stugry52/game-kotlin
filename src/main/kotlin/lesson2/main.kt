package lesson2

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene

import de.fabmax.kool.math.*
import de.fabmax.kool.scene.*

import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time

import de.fabmax.kool.pipeline.ClearColorLoad

import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.modules.ui2.UiModifier.*

// Типы предметов
enum class ItemType{
    WEAPON,
    ARMOR,
    POTION
}

// Создание класса с описанием предмета
data class  Item(
    val id: String,
    val name: String,
    val type: ItemType,
    val maxStack: Int
)

// Класс описывающий стак предмета
data class ItemStack(
    val item: Item,
    val count: Int
)

class GameState{
    val playerId = mutableStateOf("Player")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val potionTicksLeft = mutableStateOf(0)

    // Хотбар на 9 слотов, List<ItemStack?> - в ячейку хотбара можно положить только стак какго-то предмета ил null (пусто)
    val holder = mutableStateOf(
        List<ItemStack?>(9){null}
        // По умолчанию хотбар заполнен пустыми ячейками и может быть максимум 9 слотов
    )
    // Активный слот инвентаря
    val selectedSlot = mutableStateOf(0)
}
// Создание готовых предметов
val HEALING_POTION = Item(
    "potion_heal",
    "Healing Potion",
    ItemType.POTION,
    12
)

val WOOD_SWORD = Item(
    "wood_sword",
    "Wood Sword",
    ItemType.WEAPON,
    1
)

fun putIntoSlot(
    slots: List<ItemStack?>, // Текущие слоты из хотбара
    slotIndex: Int,          // Id слота в который мы кладем
    item: Item,
    addCount: Int
): List<ItemStack?>{
    // Возвращаем измененный список, но уже с новым предметом
    val newSlots = slots.toMutableList() // делаем копию списка для его редактирования
    val current = newSlots[slotIndex]    // Текущий стак в слоте (может быть null)

    if (current == null){
        // Если сллот куда хотим положить - пуст, создаем в нем новый стак
        val count = minOf(addCount, item.maxStack)
        newSlots[slotIndex] = ItemStack(item, count)
        return newSlots
    }

    // Если слот в который кладем - не пуст, стакам предметы только если они того же типа, что уже лежат в слоте
    if (current.item.id == item.id && item.maxStack > 1){
        val freeSpace = item.maxStack - current.count
        // Отнимаем от количества уже лежащий в стаке предметов от максимального допустимого количества в стаке
        val toAdd = minOf(addCount, freeSpace)
        newSlots[slotIndex] = ItemStack(item, current.count + toAdd)
        return  newSlots
    }
    return newSlots
}


fun useSelected(
    slots: List<ItemStack?>,
    slotIndex: Int
): Pair<List<ItemStack?>, ItemStack?>{
    // Пара значений нужна дял того чтобы:
    // 1 функция могла вернуть 2 результата сразу, а не один
    // Мы сейчас возвращаем 2 значения, а именно: новый хотбар + скольеко предметов в него не влезло в него

    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex] ?: return Pair(newSlots, null)

    val newCount = current.count - 1

    if (newCount <= 0){
        // Если стало 0 - значит после использования предмета, стак закончился стал пустым
        newSlots[slotIndex] = null
    }else{
        newSlots[slotIndex] = ItemStack(current.item, newCount)
    }

    return Pair(newSlots, current)
}

fun main( ) = KoolApplication{
    val game = GameState()

    // Сцена UI
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

        var potionTimerSec = 0f
        // таймер сколько действует яд

        onUpdate{
            if(game.potionTicksLeft.value > 0){
                // value - достает именно значение состояния

                potionTimerSec += Time.deltaT
                // накапливаем секунды действия яда

                if (potionTimerSec >= 1f){
                    // Прошло >= 1 секунды -> делаем 1 тик действий
                    potionTimerSec = 0f

                    game.potionTicksLeft.value --
                    // Уменьшаем время действия яда

                    game.hp.value = (game.hp.value - 2).coerceAtLeast(0)
                    // Отнимаем 2 хп за 1 тик действия яда и не пропускаем падения hp меньше 0
                }
            }else{
                potionTimerSec = 0f
                // Если яд закончил свое действие - сбросить таймер
            }
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)
        // ClearColorLoad - у нас уже есть сцена 3д и она лежит на всем экране
        // Но так же у нас теперь есть HUD сцена и по-умолчанию новая сцена перерисовывает старую
        // ClearColorLoad - говорит, не перерисовывай сцену прошлую, а наложи слоем поверх

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f,0f,0f, 0.5f), 14.dp))
                .padding(12.dp)
                // dp - (density-independent pixel) - условный пиксель, который маштабируется под плотность пикселей на разных экранах.
                // То есть в отличии от px интерфейс на dp выглядит одинаково физического размера на разных устройствах

            Column {
                Text("Игрок: ${game.playerId.use()}"){}
                Text("Hp: ${game.hp.use()}"){}
                Text("Gold: ${game.gold.use()}"){}
                Text("Действия зелья: ${game.potionTicksLeft.use()}"){}

                Row {
                    modifier.margin(top = 6.dp)

                    val slots = game.holder.use()
                    val selected = game.selectedSlot.use()

                    for (i in 0 until 9){
                        val isSelected = (i == selected)

                        Box {
                            modifier
                                .size(44.dp, 44.dp)
                                .margin(end = 6.dp)
                                .background(
                                    RoundRectBackground(
                                        if (isSelected) Color(0.2f,0.2f,1f, 0.8f) else Color(0f, 0f,0f, 0.35f),
                                        8.dp
                                    )
                                )
                                .onClick{
                                    game.selectedSlot.value = i
                                }
                            val stack = slots[i]
                            if(stack == null){
                                Text ("  "){ }
                            }else{
                                Column {
                                    modifier.padding(6.dp)

                                    Text("${stack.item.name}") {
                                        modifier.font(sizes.smallText)
                                    }

                                    Text("${stack.count}") {
                                        modifier.font(sizes.smallText)
                                    }
                                }
                            }
                        }
                    }
                    Button("Получить зелье") {
                        modifier
                            .margin(end = 8.dp)
                            .onClick{
                                val  idx = game.selectedSlot.value
                                val  updated = putIntoSlot(game.holder.value, idx, HEALING_POTION, 3)

                                game.holder.value = updated
                            }
                    }
                    Button("Деревянный меч") {
                        modifier
                            .margin(end = 8.dp)
                            .onClick{
                                val  idx = game.selectedSlot.value
                                val  updated = putIntoSlot(game.holder.value, idx, WOOD_SWORD, 1)

                                game.holder.value = updated
                            }
                    }
                }
            }
        }

    }
}






