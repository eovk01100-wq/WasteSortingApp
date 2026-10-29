package com.jiwoo.wastesorting

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 선택 로그인과 서버 기록.
 * - 로그인 안 함: 지금처럼 기기 토큰으로 모든 기능 사용 (기록도 서버에 쌓임)
 * - 가입·로그인: 다른 폰에서도 같은 계정으로 서버 기록을 볼 수 있음
 * onOpen: 서버 기록 하나를 눌렀을 때 결과 카드로 보여 주기
 */
class AccountDialog(
    private val activity: AppCompatActivity,
    private val onOpen: (HistoryDetail) -> Unit
) {

    /** 기록 창의 [내 계정 기록] 버튼: 로그인했으면 서버 기록, 아니면 계정 창 */
    fun showServerHistoryOrAccount() {
        if (GeminiClient.isLoggedIn) showServerHistory() else showAccount()
    }

    fun showAccount() {
        if (GeminiClient.isLoggedIn) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("내 계정 · ${GeminiClient.email ?: "로그인됨"}")
                .setItems(arrayOf("계정 기록 보기", "로그아웃", "계정 삭제")) { _, which ->
                    when (which) {
                        0 -> showServerHistory()
                        1 -> logout()
                        2 -> confirmDelete()
                    }
                }
                .setNegativeButton("닫기", null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("계정 (선택)")
            .setMessage(
                "로그인하지 않아도 모든 기능을 쓸 수 있어요.\n" +
                    "계정을 만들면 폰을 바꾸거나 다른 폰에서도 분석 기록을 이어서 볼 수 있어요.\n\n" +
                    "가입하면 이 폰에서 지금까지 받은 기록이 그대로 계정으로 옮겨져요."
            )
            .setPositiveButton("가입") { _, _ -> showForm(signup = true) }
            .setNeutralButton("로그인") { _, _ -> showForm(signup = false) }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showForm(signup: Boolean) {
        val email = EditText(activity).apply {
            hint = "이메일"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        }
        val password = EditText(activity).apply {
            hint = if (signup) "비밀번호 (8자 이상)" else "비밀번호"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(email)
            addView(password)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (signup) "가입" else "로그인")
            .setView(box)
            .setPositiveButton(if (signup) "가입" else "로그인") { _, _ ->
                submit(signup, email.text.toString(), password.text.toString())
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun submit(signup: Boolean, email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            toast("이메일과 비밀번호를 입력해 주세요")
            return
        }
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (signup) GeminiClient.signup(email, password) else GeminiClient.login(email, password)
                }
                toast(if (signup) "가입했어요. 이 폰의 기록이 계정으로 옮겨졌어요" else "로그인했어요")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(errorText(e))
            }
        }
    }

    private fun logout() {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { GeminiClient.logout() }
            toast("로그아웃했어요. 계정 기록은 서버에 그대로 남아 있어요")
        }
    }

    /** 탈퇴: 되돌릴 수 없으니 비밀번호를 한 번 더 받는다 */
    private fun confirmDelete() {
        val password = EditText(activity).apply {
            hint = "비밀번호"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(password)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("계정 삭제")
            .setMessage(
                "서버에 있는 계정, 분석 기록, 보낸 학습 사진이 모두 지워지고 되돌릴 수 없어요.\n" +
                    "이 폰 안의 최근 기록과 별표는 남아요.\n\n계속하려면 비밀번호를 입력해 주세요."
            )
            .setView(box)
            .setPositiveButton("삭제") { _, _ -> deleteAccount(password.text.toString()) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteAccount(password: String) {
        if (password.isBlank()) {
            toast("비밀번호를 입력해 주세요")
            return
        }
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { GeminiClient.deleteAccount(password) }
                toast("계정을 삭제했어요")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(errorText(e))
            }
        }
    }

    // ---------------- 서버 기록 ----------------

    private fun showServerHistory() {
        activity.lifecycleScope.launch {
            val page = try {
                withContext(Dispatchers.IO) { GeminiClient.history() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(errorText(e))
                return@launch
            }
            val items = page.content.orEmpty()
            if (items.isEmpty()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("계정 기록")
                    .setMessage("아직 서버에 쌓인 기록이 없어요.")
                    .setPositiveButton("닫기", null)
                    .show()
                return@launch
            }
            val adapter = object : ArrayAdapter<HistorySummary>(activity, 0, items) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = (convertView as? LinearLayout) ?: buildRow()
                    val e = getItem(position)!!
                    (row.getChildAt(0) as TextView).text = e.itemName ?: e.query ?: "-"
                    (row.getChildAt(1) as TextView).text =
                        "${e.category ?: "-"} · ${sourceName(e.source)} · ${shortTime(e.createdAt)}"
                    return row
                }
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("계정 기록 (${page.totalElements}건)")
                .setAdapter(adapter) { _, which -> openDetail(items[which].id) }
                .setNeutralButton("내 계정") { _, _ -> showAccount() }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    private fun openDetail(id: Long) {
        activity.lifecycleScope.launch {
            try {
                onOpen(withContext(Dispatchers.IO) { GeminiClient.historyDetail(id) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(errorText(e))
            }
        }
    }

    private fun buildRow(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(10), dp(24), dp(10))
        addView(TextView(context).apply { textSize = 16f; maxLines = 1 })
        addView(TextView(context).apply { textSize = 13f; alpha = 0.7f; maxLines = 1 })
    }

    private fun errorText(e: Exception): String = when (e) {
        is ServerException -> e.message ?: "요청에 실패했어요"
        is java.net.UnknownHostException, is java.net.ConnectException -> "인터넷 연결을 확인해 주세요"
        else -> "요청에 실패했어요. 다시 시도해 주세요"
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    companion object {
        fun sourceName(source: String?) = when (source) {
            "CROP" -> "카메라"
            "WHOLE" -> "화면 전체"
            "SEARCH" -> "검색"
            else -> source ?: "-"
        }

        /** "2026-10-29T23:32:23.29" → "10/29 23:32" */
        fun shortTime(iso: String?): String =
            if (iso == null || iso.length < 16) "-" else iso.substring(5, 10).replace('-', '/') + " " + iso.substring(11, 16)
    }
}
