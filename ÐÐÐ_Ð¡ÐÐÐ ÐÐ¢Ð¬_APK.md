# Как собрать APK без Android Studio (через GitHub)

## Шаг 1. Создай репозиторий на GitHub
1. Зайди на https://github.com/new
2. Название: например `AIChat`
3. Оставь Public (или Private — тоже работает)
4. **Не** ставь галочки README / .gitignore
5. Нажми Create repository

## Шаг 2. Загрузи проект
На компьютере (или в терминале GitHub):

```bash
# Распакуй AIChat.zip, зайди в папку
cd AIChat

git init
git add .
git commit -m "AI Chat app"
git branch -M main
git remote add origin https://github.com/ТВОЙ_ЛОГИН/AIChat.git
git push -u origin main
```

(Вставь свой логин и имя репозитория)

## Шаг 3. Запусти сборку
1. Открой репозиторий на GitHub
2. Вкладка **Actions**
3. Слева выбери «Сборка APK»
4. Справа кнопка **Run workflow** → Run workflow
5. Подожди 3–6 минут

## Шаг 4. Скачай APK
1. Когда сборка станет зелёной — кликни на неё
2. Внизу в разделе **Artifacts** будет файл `AIChat-debug-apk`
3. Скачай и распакуй — внутри `app-debug.apk`
4. Перекинь на телефон и установи

> При первой установке Android попросит разрешить установку из неизвестных источников.
