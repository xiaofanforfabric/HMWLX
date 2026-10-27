package org.cn.xiaofan.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/**
 * 在 Composable 里取文案。
 *
 * 内部走 [Messages] 的全局当前语言：切换语言后重组就能拿到新文案。
 * 目前只有中文一种语言包，所以这里没有监听语言变化。
 */
@Composable
@ReadOnlyComposable
fun text(key: String, vararg args: Any?): String = Messages.get(key, *args)
