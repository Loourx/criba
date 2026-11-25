package io.github.loourx.criba

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.loourx.criba.ui.CribaApp
import io.github.loourx.criba.ui.CribaTheme
import io.github.loourx.criba.ui.Muted

class MainActivity : ComponentActivity() {

    private var hasAccess by mutableStateOf(false)
    private val vm: CribaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // La app siempre es oscura: iconos claros en las barras aunque el móvil esté en modo claro.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        hasAccess = Environment.isExternalStorageManager()
        setContent {
            CribaTheme {
                if (hasAccess) CribaApp(vm) else AccessScreen { askAccess() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Se comprueba al volver de Ajustes, donde se concede el permiso.
        val now = Environment.isExternalStorageManager()
        if (now && !hasAccess) vm.reload()
        hasAccess = now
    }

    private fun askAccess() {
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(specific)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

@Composable
private fun AccessScreen(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Criba", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Para revisar tus fotos y vídeos, Criba necesita “Acceso a todos los archivos”. " +
                "Con él puede leer también los enviados y privados de WhatsApp, que la galería no enseña, " +
                "y mover lo que descartes a una carpeta de revisión.",
        )
        Text(
            "La app no tiene acceso a internet y nunca borra nada por su cuenta: el borrado final lo confirmas tú en el aviso de Android.",
            color = Muted,
        )
        Button(onClick = onAsk) { Text("Dar acceso") }
    }
}
