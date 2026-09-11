package app.lecturevault.ui

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.lecturevault.data.AppSettings
import app.lecturevault.data.SessionRepository
import app.lecturevault.data.SecretStore
import app.lecturevault.databinding.ActivityMiniTestBinding
import app.lecturevault.network.GeminiClient
import app.lecturevault.network.MultipleChoiceQuestion
import app.lecturevault.obsidian.VaultWriter
import app.lecturevault.util.applyScreenInsets
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MiniTestActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMiniTestBinding
    private var questions: List<MultipleChoiceQuestion> = emptyList()
    private var cardIndex = 0
    private var answered = false
    private var correctAnswers = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMiniTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.answerButton.setOnClickListener { answerCurrentQuestion() }
        binding.nextButton.setOnClickListener { moveNext() }
        loadQuestions()
    }

    private fun loadQuestions() {
        binding.progressText.text = "ИИ создаёт 10 вопросов…"
        lifecycleScope.launch {
            runCatching {
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
            val session = SessionRepository(applicationContext).get(sessionId) ?: error("Лекция не найдена")
            val notePath = session.noteRelativePath ?: error("Конспект ещё не готов")
            val vaultUri = AppSettings(applicationContext).vaultTreeUri ?: error("Папка Obsidian не подключена")
            val settings = AppSettings(applicationContext)
            check(settings.consent) { "Для ИИ-теста включите согласие на облачную обработку в настройках" }
            val geminiKey = SecretStore(applicationContext).getGeminiKey()
                ?: error("Добавьте ключ Gemini в настройках")
            GeminiClient(geminiKey, settings.geminiModel).generateMiniTest(
                VaultWriter(applicationContext).readLectureNote(vaultUri, notePath),
            )
            }.onSuccess {
                questions = it
                showQuestion()
            }.onFailure {
                binding.questionText.text = "Не удалось создать ИИ-тест"
                binding.progressText.text = it.message.orEmpty()
                binding.optionsGroup.visibility = View.GONE
                binding.answerButton.visibility = View.GONE
                binding.nextButton.visibility = View.GONE
                Snackbar.make(binding.root, "ИИ-тест не создан", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showQuestion() {
        if (cardIndex >= questions.size) {
            binding.questionText.text = "Мини тест завершён"
            binding.progressText.text = "Результат: $correctAnswers из 10"
            binding.answerText.visibility = View.GONE
            binding.optionsGroup.visibility = View.GONE
            binding.answerButton.visibility = View.GONE
            binding.nextButton.text = "Начать заново"
            binding.nextButton.visibility = View.VISIBLE
            return
        }
        answered = false
        val question = questions[cardIndex]
        binding.progressText.text = "Вопрос ${cardIndex + 1} из 10"
        binding.questionText.text = question.question
        binding.answerText.visibility = View.GONE
        binding.optionsGroup.removeAllViews()
        question.options.forEachIndexed { index, option ->
            binding.optionsGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = index
                text = option
                setTextColor(getColor(app.lecturevault.R.color.text_primary))
                textSize = 16f
                setPadding(8, 14, 8, 14)
            })
        }
        binding.optionsGroup.clearCheck()
        binding.optionsGroup.visibility = View.VISIBLE
        binding.answerButton.visibility = View.VISIBLE
        binding.nextButton.visibility = View.GONE
    }

    private fun answerCurrentQuestion() {
        val selectedId = binding.optionsGroup.checkedRadioButtonId
        if (selectedId == View.NO_ID) {
            Snackbar.make(binding.root, "Выберите один вариант", Snackbar.LENGTH_SHORT).show()
            return
        }
        val selectedIndex = binding.optionsGroup.findViewById<RadioButton>(selectedId).tag as Int
        val question = questions[cardIndex]
        answered = true
        binding.answerText.text = if (selectedIndex == question.correctIndex) {
            correctAnswers += 1
            "Верно. ${question.options[question.correctIndex]}"
        } else {
            "Неверно. Правильный ответ: ${question.options[question.correctIndex]}"
        }
        binding.answerText.visibility = View.VISIBLE
        binding.answerButton.visibility = View.GONE
        binding.nextButton.text = if (cardIndex == questions.lastIndex) "Завершить" else "Следующий вопрос"
        binding.nextButton.visibility = View.VISIBLE
    }

    private fun moveNext() {
        if (cardIndex >= questions.size) {
            cardIndex = 0
            correctAnswers = 0
        } else if (answered) {
            cardIndex += 1
        }
        showQuestion()
    }

    companion object { const val EXTRA_SESSION_ID = "session_id" }
}
