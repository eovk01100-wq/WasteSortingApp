package com.jiwoo.wastesorting

import com.google.gson.Gson

/** Gemini가 보낸 글을 앱에서 쓸 답변으로 바꾼다 (네트워크와 분리해서 테스트 가능) */
object GeminiParser {

    private val gson = Gson()

    fun parse(text: String): DisposalResponse {
        // 혹시 ```json ... ``` 으로 감싸서 오면 벗겨내기
        val json = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        val parsed = gson.fromJson(json, DisposalResponse::class.java)
            ?: error("AI 답변을 읽지 못했어요")
        return sanitize(parsed)
    }

    /** 표시·저장 전에 답변 정리: 빈 답변은 오류, 너무 긴 글은 자르고, 분류는 허용된 값만 */
    fun sanitize(r: DisposalResponse): DisposalResponse {
        val steps = r.steps.orEmpty()
            .filterNotNull()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(8)
            .map { it.take(200) }
        if (r.itemName.isNullOrBlank() && steps.isEmpty()) error("AI 답변이 비어 있어요")

        return r.copy(
            itemName = r.itemName?.trim()?.take(60),
            category = r.category?.trim()?.take(40),
            steps = steps,
            caution = r.caution?.trim()?.take(300),
            note = r.note?.trim()?.take(300),
            yoloClass = WasteClasses.normalize(r.yoloClass)
        )
    }
}