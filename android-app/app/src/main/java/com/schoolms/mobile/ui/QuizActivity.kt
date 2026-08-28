package com.schoolms.mobile.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.schoolms.mobile.R
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager

class QuizActivity : BaseActivity() {
    private enum class QuizMode(val titleLabel: String, val bestKey: String) {
        MATH("Math Challenge", "quiz_best_math"),
        SCIENCE("Science Test", "quiz_best_science")
    }

    private data class QuizQuestion(
        val question: String,
        val options: List<String>,
        val correctIndex: Int
    )

    private var currentMode = QuizMode.MATH
    private var currentQuestions: List<QuizQuestion> = emptyList()
    private var questionIndex = 0
    private var score = 0
    private lateinit var studentClassName: String
    private var questionTimer: CountDownTimer? = null
    private val questionDurationMs = 20_000L

    private lateinit var modeMathButton: MaterialButton
    private lateinit var modeScienceButton: MaterialButton
    private lateinit var progressText: TextView
    private lateinit var subjectBadgeText: TextView
    private lateinit var timerText: TextView
    private lateinit var questionText: TextView
    private lateinit var optionsGroup: RadioGroup
    private lateinit var nextButton: MaterialButton
    private lateinit var restartButton: MaterialButton
    private lateinit var resultCard: View
    private lateinit var resultTitle: TextView
    private lateinit var resultStars: TextView
    private lateinit var resultBody: TextView
    private lateinit var bestMathText: TextView
    private lateinit var bestScienceText: TextView
    private lateinit var cloudSetText: TextView
    private lateinit var leaderboardCard: MaterialCardView
    private lateinit var leaderboardTitle: TextView
    private lateinit var leaderboardBody: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin(Role.STUDENT)) return
        val user = SessionManager.currentUser ?: return
        studentClassName = user.className
        if (!isSeniorStudent(user.className)) {
            Toast.makeText(this, "Quizzes are available for Class 6 and above", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContentView(R.layout.activity_quiz)
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), "Learning quiz")

        modeMathButton = findViewById(R.id.mathModeButton)
        modeScienceButton = findViewById(R.id.scienceModeButton)
        progressText = findViewById(R.id.quizProgressText)
        subjectBadgeText = findViewById(R.id.quizSubjectBadgeText)
        timerText = findViewById(R.id.quizTimerText)
        questionText = findViewById(R.id.quizQuestionText)
        optionsGroup = findViewById(R.id.quizOptionsGroup)
        nextButton = findViewById(R.id.quizNextButton)
        restartButton = findViewById(R.id.quizRestartButton)
        resultCard = findViewById(R.id.quizResultCard)
        resultTitle = findViewById(R.id.quizResultTitle)
        resultStars = findViewById(R.id.quizResultStars)
        resultBody = findViewById(R.id.quizResultBody)
        bestMathText = findViewById(R.id.bestMathScoreText)
        bestScienceText = findViewById(R.id.bestScienceScoreText)
        cloudSetText = findViewById(R.id.quizCloudSetText)
        leaderboardCard = findViewById(R.id.quizLeaderboardCard)
        leaderboardTitle = findViewById(R.id.quizLeaderboardTitle)
        leaderboardBody = findViewById(R.id.quizLeaderboardBody)

        modeMathButton.setOnClickListener { switchMode(QuizMode.MATH) }
        modeScienceButton.setOnClickListener { switchMode(QuizMode.SCIENCE) }
        nextButton.setOnClickListener { handleNext() }
        restartButton.setOnClickListener { restartQuiz() }

        updateBestScoreLabels()
        switchMode(QuizMode.MATH)
    }

    private fun switchMode(mode: QuizMode) {
        currentMode = mode
        currentQuestions = loadQuestionsFor(mode)
        restartQuiz()
        styleModeButtons()
    }

    private fun restartQuiz() {
        questionTimer?.cancel()
        questionIndex = 0
        score = 0
        resultCard.visibility = View.GONE
        nextButton.visibility = View.VISIBLE
        showQuestion()
    }

    private fun showQuestion() {
        val question = currentQuestions[questionIndex]
        progressText.text = "${currentMode.titleLabel} | Question ${questionIndex + 1}/${currentQuestions.size}"
        cloudSetText.text = "Cloud set ${SchoolRepository.quizRotation(currentMode.storageMode(), studentClassName) + 1} | ${levelLabel()}"
        subjectBadgeText.text = if (currentMode == QuizMode.MATH) "Mathematics" else "Science"
        questionText.text = question.question
        optionsGroup.removeAllViews()
        question.options.forEachIndexed { index, option ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = option
                textSize = 16f
                tag = index
                setPadding(8, 16, 8, 16)
                setBackgroundResource(R.drawable.bg_quiz_option)
            }
            optionsGroup.addView(button)
        }
        nextButton.text = if (questionIndex == currentQuestions.lastIndex) "Finish quiz" else "Next question"
        startQuestionTimer()
    }

    private fun handleNext() {
        val selectedId = optionsGroup.checkedRadioButtonId
        if (selectedId == View.NO_ID) {
            Toast.makeText(this, "Choose one answer first", Toast.LENGTH_SHORT).show()
            return
        }
        questionTimer?.cancel()
        val selectedButton = findViewById<RadioButton>(selectedId)
        val chosenIndex = selectedButton.tag as? Int ?: -1
        if (chosenIndex == currentQuestions[questionIndex].correctIndex) {
            score += 1
        }

        if (questionIndex == currentQuestions.lastIndex) {
            showResult()
        } else {
            questionIndex += 1
            showQuestion()
        }
    }

    private fun showResult() {
        questionTimer?.cancel()
        resultCard.visibility = View.VISIBLE
        nextButton.visibility = View.GONE
        val total = currentQuestions.size
        val percent = (score * 100) / total
        val currentUser = SessionManager.currentUser ?: return
        val advanced = SchoolRepository.completeQuizRound(
            user = currentUser,
            mode = currentMode.storageMode(),
            className = studentClassName,
            score = score,
            total = total
        )
        resultTitle.text = "${currentMode.titleLabel} complete"
        resultStars.text = starsFor(percent)
        resultBody.text =
            "Score: $score/$total\nAccuracy: $percent%\n${resultMessage(percent)}\n" +
                if (advanced) "A new cloud question set is now ready."
                else "This quiz set stays active for 24 hours before it changes."
        saveBestScoreIfNeeded(score, total)
        updateBestScoreLabels()
        bindLeaderboard()
    }

    private fun resultMessage(percent: Int): String = when {
        percent >= 80 -> "Excellent work. You are ready for tougher questions."
        percent >= 60 -> "Good job. A little more practice will sharpen you further."
        else -> "Keep practicing. Try again and improve your score."
    }

    private fun styleModeButtons() {
        styleModeButton(modeMathButton, currentMode == QuizMode.MATH)
        styleModeButton(modeScienceButton, currentMode == QuizMode.SCIENCE)
    }

    private fun styleModeButton(button: MaterialButton, selected: Boolean) {
        if (selected) {
            button.setBackgroundResource(R.drawable.bg_quiz_mode_selected)
            button.setTextColor(getColor(android.R.color.white))
        } else {
            button.setBackgroundResource(R.drawable.bg_quiz_mode_unselected)
            button.setTextColor(getColor(R.color.text_secondary))
        }
    }

    private fun saveBestScoreIfNeeded(score: Int, total: Int) {
        val prefs = getSharedPreferences("quiz_progress", MODE_PRIVATE)
        val bestValue = prefs.getString(currentMode.bestKey, null)
        val bestScore = bestValue?.substringBefore("/")?.toIntOrNull() ?: -1
        if (score > bestScore) {
            prefs.edit().putString(currentMode.bestKey, "$score/$total").apply()
        }
    }

    private fun updateBestScoreLabels() {
        val prefs = getSharedPreferences("quiz_progress", MODE_PRIVATE)
        bestMathText.text = "Best math score: ${prefs.getString(QuizMode.MATH.bestKey, "--/5")}"
        bestScienceText.text = "Best science score: ${prefs.getString(QuizMode.SCIENCE.bestKey, "--/5")}"
    }

    override fun onRepositoryChanged() {
        super.onRepositoryChanged()
        if (::cloudSetText.isInitialized) {
            currentQuestions = loadQuestionsFor(currentMode)
            if (questionIndex >= currentQuestions.size) {
                questionIndex = 0
            }
            if (resultCard.visibility != View.VISIBLE) {
                showQuestion()
            } else {
                cloudSetText.text = "Cloud set ${SchoolRepository.quizRotation(currentMode.storageMode(), studentClassName) + 1} | ${levelLabel()}"
                bindLeaderboard()
            }
        }
    }

    private fun loadQuestionsFor(mode: QuizMode): List<QuizQuestion> {
        return SchoolRepository.quizQuestionsFor(mode.storageMode(), studentClassName).map {
            QuizQuestion(
                question = it.first,
                options = it.second,
                correctIndex = it.third
            )
        }
    }

    private fun levelLabel(): String {
        val classNumber = Regex("(\\d+)").find(studentClassName)?.groupValues?.get(1)?.toIntOrNull()
            ?: return "Junior level"
        return if (classNumber >= 8) "Senior level" else "Junior level"
    }

    private fun startQuestionTimer() {
        questionTimer?.cancel()
        questionTimer = object : CountDownTimer(questionDurationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                timerText.text = "Time left: ${millisUntilFinished / 1000}s"
            }

            override fun onFinish() {
                timerText.text = "Time left: 0s"
                Toast.makeText(this@QuizActivity, "Time up. Moving to the next question.", Toast.LENGTH_SHORT).show()
                if (questionIndex == currentQuestions.lastIndex) {
                    showResult()
                } else {
                    questionIndex += 1
                    showQuestion()
                }
            }
        }.start()
    }

    private fun starsFor(percent: Int): String = when {
        percent >= 90 -> "★★★★★"
        percent >= 75 -> "★★★★☆"
        percent >= 60 -> "★★★☆☆"
        percent >= 40 -> "★★☆☆☆"
        else -> "★☆☆☆☆"
    }

    private fun bindLeaderboard() {
        val currentUser = SessionManager.currentUser ?: return
        val rows = SchoolRepository.quizLeaderboardRows(
            mode = currentMode.storageMode(),
            className = studentClassName,
            currentUsername = currentUser.username
        )
        leaderboardTitle.text = if (currentMode == QuizMode.MATH) {
            "Top math scores"
        } else {
            "Top science scores"
        }
        leaderboardBody.text = if (rows.isEmpty()) {
            "No scores recorded yet. Finish a round to create the first leaderboard entry."
        } else {
            rows.joinToString("\n\n") { "${it.title}\n${it.subtitle}" + (it.badge?.let { badge -> if (badge == "You") "\n$badge" else "" } ?: "") }
        }
        leaderboardCard.visibility = View.VISIBLE
    }

    private fun isSeniorStudent(className: String): Boolean {
        val normalized = className.trim().lowercase()
        val number = Regex("(\\d+)").find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return number >= 6
    }

    private fun QuizMode.storageMode(): String = when (this) {
        QuizMode.MATH -> "math"
        QuizMode.SCIENCE -> "science"
    }

    override fun onDestroy() {
        questionTimer?.cancel()
        super.onDestroy()
    }
}
