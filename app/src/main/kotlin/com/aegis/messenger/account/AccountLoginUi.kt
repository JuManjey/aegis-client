package com.aegis.messenger.account

import com.aegis.core.common.account.AccountLogin
import com.aegis.messenger.R

fun AccountLogin.Error.stringRes(): Int = when (this) {
    AccountLogin.Error.Empty -> R.string.account_login_empty
    AccountLogin.Error.TooShort -> R.string.account_login_too_short
    AccountLogin.Error.TooLong -> R.string.account_login_too_long
    AccountLogin.Error.InvalidChars -> R.string.account_login_invalid
    AccountLogin.Error.LooksLikeEmail -> R.string.account_login_looks_email
    AccountLogin.Error.LooksLikePhone -> R.string.account_login_looks_phone
}
