package in.nispax.sruvik

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import in.nispax.sruvik.ui.SruvikApp
import in.nispax.sruvik.ui.theme.SruvikTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SruvikTheme { SruvikApp() } }
    }
}
