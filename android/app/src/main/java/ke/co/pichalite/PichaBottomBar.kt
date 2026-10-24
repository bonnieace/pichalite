package ke.co.pichalite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun PichaBottomBar(selected: Int, ready: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().background(Paper).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Ink).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Studio" to Icons.Outlined.PhotoLibrary, "Ready" to Icons.Outlined.CheckCircle, "Wrapped" to Icons.Outlined.AutoAwesome).forEachIndexed { index, (label, icon) ->
                val active = selected == index
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .background(if (active) Orange else Color.Transparent)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                        .heightIn(min = 64.dp).padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    BadgedBox(badge = {
                        if (index == 1 && ready > 0) Badge(containerColor = Paper, contentColor = Ink,
                            modifier = Modifier.semantics { contentDescription = "$ready finished photos" }) {
                            Text(if (ready > 999) "999+" else ready.toString(), fontSize = 9.sp)
                        }
                    }) { Icon(icon, null, tint = if (active) Color.White else Color(0xFFD4D9D4), modifier = Modifier.size(23.dp)) }
                    Text(label, color = if (active) Color.White else Color(0xFFD4D9D4), fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}
