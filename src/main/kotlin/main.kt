import de.fabmax.kool.KoolApplication           // Запускает движок
import de.fabmax.kool.addScene                  // Функция добавления сцены (Игра, UI, уровень, Меню)

import de.fabmax.kool.math.Vec3f                // 3д вектор (x,y,z)
import de.fabmax.kool.math.deg                  // deg - превращение числа в градусы (углов)
import de.fabmax.kool.modules.ksl.KslLitShader
import de.fabmax.kool.scene.*                   // Сцена, камера по умолчанию, создание фигур, освещение

import de.fabmax.kool.modules.ksl.KslPbrShader  // Шейдеры - материал объекта
import de.fabmax.kool.util.Color                // Цветовая палитра (RGBA)
import de.fabmax.kool.util.Time                 // Время - Time.deltaT - сколько секунд пройдет между кадрами

import de.fabmax.kool.pipeline.ClearColorLoad   // Чтобы не стирать элемент уже отрисованый на экране. UI - всегда поверх всего на сцене

import de.fabmax.kool.modules.ui2.*             // HTML - создание Текста, кнопок, панелей, Row, Column, mutableStateOf...
import de.fabmax.kool.modules.ui2.UiModifier.*  // CSS - padding(),  align(), background(), size()

class GameState{
    val playerId = mutableStateOf("Player")
    // mutableStateOf - создает состояние, за которым будет следить UI элементы
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val potionTicksLeft = mutableStateOf(0)
    // Тики - условная единится времени, на которую мы описаемся, чтобы не зависеть от клиентского FPS
    // На нашем примере простом 1 тик = 1 секунде (объем тика определяется разработчиком игр самостоятельно)

}

// KoolApplication - указывает, что запускаемое приложение - это приложение написанное на KOOL
fun main() = KoolApplication {
    // Запуск движка
    val game = GameState()

    // 1 сцена - ИГРОВОЙ 3Д МИР
    addScene {
        defaultOrbitCamera()
        // Готовая камера - по умолчанию крутится на пкм вокруг точки сцены

        addColorMesh {
            generate { // Генерация геометрии в сцене
                cube{  // Сгенерировать пресет в виде куба
                    colored()
                    // добавляем цвет в стороны куба
                }
            }
            shader = KslPbrShader {
                // Называем материал фигуре
                color { vertexColor() }
                // Берем подготовленные цвета из сторон куба
                metallic(0f)       // Металлизация объекта (0f - плвстик / 1f - отполированный кусок металла)
                roughness(0.25f)   // Шероховатость (0 - глянец / 1 - матовый)
            }

            onUpdate{
                // Метод вызывается каждый кадр
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
                // rotate(угол, ось)
                // 45 - градусы в секунды
                // * Time.deltaT - сколько прошло секунд между кадрами и на сколько уже повернулся куб
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-10f, -10f, -10f))
            // Установили в позицию немного дальше от центра где лежит куб
            setColor(Color.WHITE, 5f)
            // Включить белый свет с яркостью 5 кельвинов
        }

        // Логика игровая
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
        // Сцена для HUD

            setupUiScene(ClearColorLoad)
            // Режим сцены в фиксированный UI

            addPanelSurface {
                // Создать панель интерфейса (div)
                modifier
                    .size(360.dp, 210.dp)
                    .align(AlignmentX.Start, AlignmentY.Top)
                    .padding(16.dp)
                    // Приклеиваем интерфейс к левой верхней части экрана
                    .background(RoundRectBackground(Color(0f,0f,0f, 0.5f), 14.dp))


                Column {
                    Text("Игрок: ${game.playerId.use()}"){}
                    Text("Hp: ${game.hp.use()}"){}
                    Text("Gold: ${game.gold.use()}"){}
                    Text("Действия зелья: ${game.potionTicksLeft.use()}"){}
                }
            }

    }
}
