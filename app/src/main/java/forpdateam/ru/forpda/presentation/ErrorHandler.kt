package forpdateam.ru.forpda.presentation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import forpdateam.ru.forpda.R
import forpdateam.ru.forpda.client.GoogleCaptchaException
import forpdateam.ru.forpda.client.OkHttpResponseException
import forpdateam.ru.forpda.common.ArticleCommentsUserMessage
import forpdateam.ru.forpda.model.interactors.news.ArticleLoadException
import timber.log.Timber
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import javax.inject.Inject

/**
 * Created by radiationx on 23.02.18.
 */
class ErrorHandler @Inject constructor(
        private val router: TabRouter,
        @ApplicationContext private val context: Context
) : IErrorHandler {

    override fun handle(throwable: Throwable,  messageListener: ((Throwable, String?) -> Unit)?) {
        // Отмена корутины/подписки — часть нормального жизненного цикла, не показываем пользователю
        // (иначе во viewModelScope при закрытии экрана всплывает «GP/Job was cancelled»).
        if (isCancellation(throwable)) {
            return
        }
        Timber.e(throwable, "Error handled: %s", throwable.message ?: "unknown")
        val message = getMessage(throwable)
        if (messageListener != null) {
            messageListener.invoke(throwable, message)
        } else {
            router.showSystemMessage(message)
        }
    }

    /** Усечение URL для тоста: убираем схему и хост, оставляем только путь + query (до 120 символов). */
    private fun shortUrl(url: String): String {
        val stripped = url
                .removePrefix("https://")
                .removePrefix("http://")
                .substringAfter('/', missingDelimiterValue = url)
        val withSlash = if (url.contains("://")) "/$stripped" else url
        return if (withSlash.length > 120) withSlash.take(117) + "..." else withSlash
    }

    private fun isCancellation(t: Throwable): Boolean {
        var c: Throwable? = t
        while (c != null) {
            if (c is CancellationException) return true
            c = c.cause
        }
        return false
    }

    private fun getMessage(throwable: Throwable): String {
        timeoutUserMessage(throwable)?.let { return it }
        networkErrorUserMessage(throwable)?.let { return it }
        when (throwable) {
            is GoogleCaptchaException ->
                return context.getString(R.string.network_error_cloudflare)
            is OkHttpResponseException ->
                when (throwable.code) {
                    401 -> return context.getString(R.string.network_error_unauthorized)
                    403 -> return context.getString(R.string.network_error_forbidden)
                    404 -> return context.getString(R.string.network_error_not_found, shortUrl(throwable.url))
                    429 -> return context.getString(R.string.network_error_rate_limit)
                }
        }
        if (throwable is ArticleLoadException) {
            return throwable.message ?: context.getString(R.string.network_error_news)
        }
        // Check for auth-related messages
        val msg = throwable.message.orEmpty()
        if (msg.contains("авторизация", ignoreCase = true) || msg.contains("auth", ignoreCase = true)) {
            return msg
        }
        if (msg.contains("empty after template", ignoreCase = true) ||
                msg.contains("empty after parse", ignoreCase = true)) {
            return context.getString(R.string.network_error_news)
        }
        if (msg == "comments_html_present_but_parse_empty" ||
                msg == "comments_count_positive_but_no_source" ||
                msg == "comment_list_shell_unresolved_positive_count" ||
                msg.startsWith("parsed_zero_with_hint_")) {
            return ArticleCommentsUserMessage.forReason(msg)
        }
        return msg
    }

    /**
     * RxJava `timeout()` даёт английское «The source did not signal…» — показываем нормальный текст.
     * Цепочка cause учитывает обёртки вокруг [TimeoutException].
     */
    private fun timeoutUserMessage(t: Throwable): String? {
        var c: Throwable? = t
        while (c != null) {
            if (c is TimeoutException || c is SocketTimeoutException) {
                return context.getString(R.string.network_error_timeout)
            }
            val msg = c.message
            if (msg != null && msg.contains("did not signal an event", ignoreCase = true)) {
                return context.getString(R.string.network_error_timeout)
            }
            c = c.cause
        }
        return null
    }

    /**
     * DNS и сетевые ошибки: UnknownHostException, SSLException, etc.
     * Показываем понятное сообщение вместо технического текста.
     */
    private fun networkErrorUserMessage(t: Throwable): String? {
        var c: Throwable? = t
        while (c != null) {
            if (c is UnknownHostException) {
                val msg = c.message.orEmpty()
                return when {
                    msg.contains("4pda.to", ignoreCase = true) ->
                        context.getString(R.string.network_error_forum_host)
                    else ->
                        context.getString(R.string.network_error_host)
                }
            }
            // SSL ошибки часто связаны с блокировками
            val msg = c.message.orEmpty()
            if (c.javaClass.simpleName.contains("SSL", ignoreCase = true) ||
                msg.contains("SSL", ignoreCase = true) ||
                msg.contains("TLS", ignoreCase = true) ||
                msg.contains("certificate", ignoreCase = true)) {
                return context.getString(R.string.network_error_ssl)
            }
            c = c.cause
        }
        return null
    }
}