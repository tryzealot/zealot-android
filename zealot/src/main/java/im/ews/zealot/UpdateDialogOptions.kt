package im.ews.zealot

/** Text and dismissal behavior for the built-in update dialog. Null text uses localized defaults. */
data class UpdateDialogOptions @JvmOverloads constructor(
    val title: String? = null,
    val updateButtonText: String? = null,
    val laterButtonText: String? = null,
    val cancelable: Boolean = true
) {
    init {
        require(title == null || title.isNotBlank()) { "title must not be blank" }
        require(updateButtonText == null || updateButtonText.isNotBlank()) {
            "updateButtonText must not be blank"
        }
        require(laterButtonText == null || laterButtonText.isNotBlank()) {
            "laterButtonText must not be blank"
        }
    }
}
