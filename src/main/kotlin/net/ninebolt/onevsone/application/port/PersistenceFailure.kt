package net.ninebolt.onevsone.application.port

/**
 * 永続化・外部参照処理の失敗。破損ファイルや I/O エラーを示す。
 * 通常のユーザー向け拒否(参加不可等)はこれではなくユースケース結果で表す。
 */
class PersistenceFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
