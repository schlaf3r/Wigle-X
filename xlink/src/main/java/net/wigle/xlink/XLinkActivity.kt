package net.wigle.xlink

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The X-Link screen (Settings › X-Link), after RF Sentinel's Sentinel Link screen: this phone's name, Link up /
 * Join with a 6-digit code, sharing on/off, the linked phones, and Leave.
 *
 * The phone the others join is the main phone: it records and uploads what every phone of the group records.
 */
class XLinkActivity : Activity() {

    private lateinit var roleView: TextView
    private lateinit var statusView: TextView
    private lateinit var nameEdit: EditText
    private lateinit var shareSwitch: Switch
    private lateinit var linkUpButton: Button
    private lateinit var joinButton: Button
    private lateinit var leaveButton: Button
    private lateinit var list: LinearLayout
    private var pairDialog: AlertDialog? = null
    private var pendingPair: Boolean? = null
    private val ui = Handler(Looper.getMainLooper())
    private val linkChanged: () -> Unit = { refresh() }
    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 5_000L) }
    }

    private fun pad(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "X-Link"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(16), pad(16), pad(16), pad(24))
        }
        fun header(text: String) = TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = true
            setPadding(0, pad(18), 0, pad(6))
        }
        fun body(text: String = "") = TextView(this).apply { this.text = text; textSize = 15f; setPadding(0, pad(2), 0, pad(2)) }

        root.addView(body("Link Wigle-X phones over Bluetooth. The phone the others join is the main phone: it records " +
            "everything the group records and uploads it with its own. The other phones are nodes and send what they " +
            "record to the main phone while they scan. Nothing goes through the internet; the link is encrypted."))

        roleView = body().apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, pad(12), 0, pad(2)) }
        statusView = body()
        root.addView(roleView); root.addView(statusView)

        root.addView(header("This phone"))
        nameEdit = EditText(this).apply {
            hint = "This phone's name"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(LinkStore.myName(this@XLinkActivity))
            setOnEditorActionListener { _, _, _ -> saveName(); false }
            setOnFocusChangeListener { _, focus -> if (!focus) saveName() }
        }
        root.addView(nameEdit)
        shareSwitch = Switch(this).apply {
            text = "Share with linked phones"
            textSize = 15f
            setPadding(0, pad(10), 0, pad(10))
            isChecked = LinkStore.enabled(this@XLinkActivity)
            setOnCheckedChangeListener { _, on ->
                LinkStore.setEnabled(this@XLinkActivity, on)
                XLink.onSettingsChanged?.run()
                refresh()
            }
        }
        root.addView(shareSwitch)

        root.addView(header("Link phones"))
        linkUpButton = Button(this).apply { setOnClickListener { pair(host = true) } }
        joinButton = Button(this).apply { text = "Join a main phone"; setOnClickListener { pair(host = false) } }
        root.addView(linkUpButton); root.addView(joinButton)
        root.addView(body("Keep the phones close. Tap Link up on the main phone (or any phone already in the group) and " +
            "Join on the new one, then compare the 6-digit codes and tap Confirm on both.").apply { textSize = 13f })

        root.addView(header("Linked phones"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        leaveButton = Button(this).apply { text = "Leave the group"; setOnClickListener { confirmLeave() } }
        root.addView(leaveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = pad(20) })

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        PhoneLink.addListener(linkChanged)
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        PhoneLink.removeListener(linkChanged)
        ui.removeCallbacks(ticker)
        saveName()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (pairDialog?.isShowing == true) PhoneLink.cancelPairing()
    }

    private fun saveName() {
        val name = nameEdit.text.toString()
        if (name.trim() == LinkStore.myName(this)) return
        LinkStore.setMyName(this, name)
        PhoneLink.meChanged()
    }

    private fun refresh() {
        val group = LinkStore.hasGroup(this)
        val main = LinkStore.isMain(this)
        roleView.text = when {
            !group -> "Not linked yet"
            main -> "This phone is the main phone: it records and uploads the group's observations"
            else -> "This phone is a node: it sends what it records to the main phone"
        }
        statusView.text = XLink.status().ifEmpty {
            if (group) "X-Link starts when Wigle-X scans" + if (!LinkStore.enabled(this)) " (sharing is off)" else ""
            else "Link up to make this the main phone, or join one"
        }
        linkUpButton.text = if (!group) "Link up (make this the main phone)" else "Link up another phone"
        joinButton.visibility = if (group && main) View.GONE else View.VISIBLE
        shareSwitch.isEnabled = group
        leaveButton.visibility = if (group) View.VISIBLE else View.GONE

        list.removeAllViews()
        val members = LinkStore.members(this)
        if (members.isEmpty()) {
            list.addView(TextView(this).apply { text = if (group) "No other phones yet" else "—"; textSize = 15f })
            return
        }
        val now = System.currentTimeMillis()
        val linkedIds = PhoneLink.linked.map { it.id }.toSet()
        for (m in members) {
            val heard = PhoneLink.lastHeard[m.id]
            val detail = when {
                m.id in linkedIds -> "● Linked now"
                heard != null -> "Last heard ${ago(now - heard)}"
                else -> "Not heard yet this run"
            }
            list.addView(TextView(this).apply {
                textSize = 15f
                setPadding(0, pad(6), 0, pad(6))
                text = "${m.name}\n$detail"
                if (m.id in linkedIds) setTextColor(0xFF4CAF50.toInt())
            })
        }
    }

    private fun ago(ms: Long): String = when {
        ms < 60_000L -> "just now"
        ms < 3_600_000L -> "${ms / 60_000L} min ago"
        ms < 86_400_000L -> "${ms / 3_600_000L} h ago"
        else -> "${ms / 86_400_000L} days ago"
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this)
            .setTitle("Leave the group?")
            .setMessage(if (LinkStore.isMain(this))
                "This is the main phone. The nodes stop having anywhere to send their observations until they join a new main phone."
            else "This phone stops sending to the main phone and forgets the group. To link again, join the main phone.")
            .setPositiveButton("Leave") { _, _ ->
                LinkStore.leave(this)
                PhoneLink.groupChanged()
                XLink.onSettingsChanged?.run()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pair(host: Boolean) {
        val others = LinkStore.members(this).size
        if (!host && LinkStore.hasGroup(this)) {
            AlertDialog.Builder(this)
                .setTitle("Join another main phone?")
                .setMessage("This phone is in a group with $others other phone${if (others == 1) "" else "s"}. Joining leaves it " +
                    "for the other phone's group. To add a phone to this group instead, tap Link up here and Join on the new phone.")
                .setPositiveButton("Join") { _, _ -> withPermission(false) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        withPermission(host)
    }

    private val wanted = if (Build.VERSION.SDK_INT >= 31) arrayOf(
        android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_ADVERTISE,
        android.Manifest.permission.BLUETOOTH_SCAN) else emptyArray()

    private fun withPermission(host: Boolean) {
        if (PhoneLink.canUse(this)) { startPairing(host); return }
        pendingPair = host
        requestPermissions(wanted, REQUEST_NEARBY)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NEARBY) return
        val host = pendingPair ?: return
        pendingPair = null
        if (PhoneLink.canUse(this)) startPairing(host)
        else Toast.makeText(this, "X-Link needs \"Nearby devices\" to find the other phones", Toast.LENGTH_LONG).show()
    }

    private fun startPairing(host: Boolean) {
        val messageView = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER }
        val codeView = TextView(this).apply {
            textSize = 40f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setPadding(0, pad(16), 0, pad(8))
            visibility = View.GONE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(24), pad(16), pad(24), 0)
            addView(codeView); addView(messageView)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (host) "Link up" else "Join a main phone")
            .setView(content)
            .setPositiveButton("Confirm", null)
            .setNegativeButton("Cancel") { _, _ -> PhoneLink.cancelPairing() }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                setOnClickListener { isEnabled = false; PhoneLink.confirmCode() }
            }
        }
        dialog.show()
        pairDialog = dialog

        val listener = object : PhoneLink.PairListener {
            override fun onStatus(text: String) { messageView.text = text }
            override fun onCode(code: String) {
                codeView.text = code.substring(0, 3) + " " + code.substring(3)
                codeView.visibility = View.VISIBLE
                messageView.text = "Check that the other phone shows the same code, then tap Confirm on both. Different code? Cancel."
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            }
            override fun onLinked(name: String) {
                if (pairDialog === dialog) pairDialog = null
                dialog.dismiss()
                XLink.onSettingsChanged?.run()
                refresh()
                AlertDialog.Builder(this@XLinkActivity)
                    .setTitle("Linked")
                    .setMessage("Linked with $name. " + (if (LinkStore.isMain(this@XLinkActivity))
                        "This is the main phone: the group's observations are recorded here while both phones scan."
                    else "This phone sends its observations to the main phone while both phones scan."))
                    .setPositiveButton("OK", null)
                    .show()
            }
            override fun onEnded(message: String) {
                if (pairDialog === dialog) pairDialog = null
                dialog.dismiss()
                Toast.makeText(this@XLinkActivity, message, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
        if (host) PhoneLink.host(this, listener) else PhoneLink.join(this, listener)
    }

    private companion object {
        const val REQUEST_NEARBY = 7301
    }
}
