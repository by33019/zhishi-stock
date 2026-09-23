/**
 * 触发浏览器保存一个内存中的文件。
 *
 * <h2>为什么用 object URL 而不是 data URL</h2>
 * 导出文件实测 78 KB，但上限（契约 §9.2）是 5,000 行。base64 的 data URL 会把体积再放大 1/3，
 * 而且要整串塞进 `href`——部分浏览器对超长 data URL 直接静默失败。
 *
 * <h2>为什么 revoke 放到下一轮宏任务</h2>
 * `link.click()` 之后立即 `revokeObjectURL` 会让部分浏览器来不及读取该 URL，
 * 表现是**点击没有任何反应**（既不下载也不报错）。
 * 代价是一个临时 URL 多存活一瞬，可以忽略。
 */
export function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}
