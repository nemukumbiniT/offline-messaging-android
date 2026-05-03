// DiscoveryScreen - Activity placeholder for manual peer discovery workflows.
// Created by Siyabonga Popela.
// Date: 2025-09-20
package ui
import android.content.Intent
import android.os.Bundle
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.nexausingnearby.R
import com.google.android.material.bottomnavigation.BottomNavigationView

class DiscoveryScreen : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var listView: ListView
    private lateinit var bottomNavigation: BottomNavigationView

    // onCreate: inflates layout, wires placeholder list, and sets up navigation bar.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discovery)

        statusText = findViewById(R.id.discoveryStatus)
        listView = findViewById(R.id.discovery_list_view)
        bottomNavigation = findViewById(R.id.bottomNavigation)

        // Waiting for real discovery data; keep list empty for now.
        listView.adapter = null

        setupBottomNavigation()
        // Siyabonga: placeholder until real discovery list arrives—keeps nav consistent meanwhile.
    }

    private fun setupBottomNavigation() {
        bottomNavigation.selectedItemId = R.id.navigation_add

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    navigateToActivity(ChatsScreen::class.java)
                    true
                }
                R.id.navigation_add -> true
                R.id.nav_settings -> {
                    navigateToActivity(SettingsScreen::class.java)
                    true
                }
                R.id.nav_qr -> {
                    startActivity(Intent(this, QRCodeActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun navigateToActivity(activityClass: Class<*>) {
        if (this::class.java != activityClass) {
            val intent = Intent(this, activityClass)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}





