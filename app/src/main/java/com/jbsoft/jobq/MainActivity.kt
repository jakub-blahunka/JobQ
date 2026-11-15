package com.jbsoft.jobq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jbsoft.jobq.ui.navigation.JobQNavigation
import com.jbsoft.jobq.ui.theme.JobQTheme

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			JobQTheme {
				JobQNavigation()
			}
		}
	}
}
