package app.lecturevault.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import app.lecturevault.data.AppSettings
import app.lecturevault.data.SessionRepository
import app.lecturevault.databinding.ActivityMiniTestBinding
import app.lecturevault.obsidian.VaultWriter
import app.lecturevault.util.applyScreenInsets
import com.google.android.material.snackbar.Snackbar

class MiniTestActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMiniTestBinding
    private var cards: List<MiniTestCard> = emptyList()
    private var cardIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMiniTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.showAnswerButton.setOnClickListener { binding.answerText.visibility = View.VISIBLE }
        binding.nextButton.setOnClickListener {
            cardIndex += 1
            showCard()
        }
        loadCards()
    }

    private fun loadCards() {
        runCatching {
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
            val session = SessionRepository(applicationContext).get(sessionId) ?: error("Лекция не найдена")
            val notePath = session.noteRelativePath ?: error("Конспект ещё не готов")
            val vaultUri = AppSettings(applicationContext).vaultTreeUri ?: error("Папка Obsidian не подключена")
            MiniTestGenerator.generate(VaultWriter(applicationContext).readLectureNote(vaultUri, notePath))
        }.onSuccess {
            cards = it
            showCard()
        }.onFailure {
            binding.questionText.text = "Не удалось подготовить мини тест"
            binding.progressText.text = it.message.orEmpty()
            binding.showAnswerButton.visibility = View.GONE
            binding.nextButton.visibility = View.GONE
            Snackbar.make(binding.root, "Не удалось создать мини тест", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun showCard() {
        if (cards.isEmpty()) {
            binding.questionText.text = "В конспекте пока недостаточно материала для карточек"
            binding.progressText.text = "Попробуйте создать более подробный конспект"
            binding.showAnswerButton.visibility = View.GONE
            binding.nextButton.visibility = View.GONE
            return
        }
        if (cardIndex >= cards.size) {
            binding.questionText.text = "Мини тест завершён"
            binding.progressText.text = "Повторите карточки ещё раз, если хотите закрепить тему"
            binding.answerText.visibility = View.GONE
            binding.showAnswerButton.visibility = View.GONE
            binding.nextButton.text = "Начать заново"
            binding.nextButton.visibility = View.VISIBLE
            binding.nextButton.setOnClickListener { cardIndex = 0; showCard() }
            return
        }
        val card = cards[cardIndex]
        binding.progressText.text = "Вопрос ${cardIndex + 1} из ${cards.size}"
        binding.questionText.text = card.question
        binding.answerText.text = card.answer
        binding.answerText.visibility = View.GONE
        binding.showAnswerButton.visibility = View.VISIBLE
        binding.nextButton.text = if (cardIndex == cards.lastIndex) "Завершить" else "Следующий вопрос"
        binding.nextButton.visibility = View.VISIBLE
    }

    companion object { const val EXTRA_SESSION_ID = "session_id" }
}
