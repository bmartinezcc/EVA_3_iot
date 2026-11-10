package com.example.eva_2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.eva_2.databinding.FragmentPanelBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import java.security.SecureRandom

/**
 * Panel principal:
 *  - Todos: ven en tiempo real el estado del dispositivo (ESP32) guardado en Firestore.
 *  - Solo ADMIN: envían comandos (abrir/cerrar, agregar/eliminar tarjetas y claves).
 *    Los comandos se escriben en Firestore y el puente de la Raspberry los publica por MQTT.
 */
class PanelFragment : Fragment() {

    private var _binding: FragmentPanelBinding? = null
    private val binding get() = _binding!!
    private lateinit var sesion: SessionManager

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val devRef by lazy { db.collection("dispositivos").document(Config.DISPOSITIVO_ID) }
    private var registro: ListenerRegistration? = null

    private val regexUid = Regex("^[0-9A-Fa-f]{8}$")
    private val regexClave = Regex("^[0-9]{4,8}$")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPanelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sesion = SessionManager(requireContext().applicationContext)

        if (FirebaseAuth.getInstance().currentUser == null) {
            sesionExpirada(); return
        }

        binding.tvSaludo.text = "Hola, ${sesion.nombre} (${sesion.rol})"
        binding.layoutAdmin.visibility = if (sesion.esAdmin) View.VISIBLE else View.GONE

        binding.btnAbrir.setOnClickListener { enviarComando("abrir") }
        binding.btnCerrar.setOnClickListener { enviarComando("cerrar") }
        binding.btnAddTarjeta.setOnClickListener { comandoTarjeta("add_tarjeta") }
        binding.btnDelTarjeta.setOnClickListener { comandoTarjeta("del_tarjeta") }
        binding.btnAddClave.setOnClickListener { comandoClave("add_clave") }
        binding.btnDelClave.setOnClickListener { comandoClave("del_clave") }
        binding.btnGenerarClave.setOnClickListener { generarClave() }
        binding.btnHistorial.setOnClickListener {
            findNavController().navigate(R.id.action_panelFragment_to_historialFragment)
        }
        binding.btnCerrarSesion.setOnClickListener { cerrarSesion() }
    }

    override fun onStart() {
        super.onStart()
        if (FirebaseAuth.getInstance().currentUser == null) return
        // Escucha en tiempo real: cada cambio del ESP32 llega solo a la app
        registro = devRef.addSnapshotListener { snap, e -> mostrarEstado(snap, e) }
    }

    override fun onStop() {
        registro?.remove()
        registro = null
        super.onStop()
    }

    private fun mostrarEstado(snap: DocumentSnapshot?, e: Exception?) {
        val b = _binding ?: return
        if (e != null) {
            val sinPermiso = e is FirebaseFirestoreException &&
                e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
            b.tvEstado.text = if (sinPermiso) "Sin permisos: revisa las reglas de Firestore"
            else getString(R.string.error_red)
            return
        }
        if (snap == null || !snap.exists()) {
            b.tvEstado.text = "Aún no hay datos del dispositivo.\n" +
                "Revisa que el ESP32 (Wokwi) y el puente de la Raspberry estén en marcha."
            return
        }
        val tEstado = snap.getTimestamp("estadoEn")?.toDate()?.time ?: 0L
        val tDatos = snap.getTimestamp("actualizado")?.toDate()?.time ?: 0L
// Si llegaron datos después del último aviso "offline", el dispositivo volvió a conectarse
        val enLinea = snap.getString("estado") == "online" || tDatos > tEstado
        b.tvEstado.text = buildString {
            append("Dispositivo: ${Config.DISPOSITIVO_ID}\n")
            append("Conexión: ${if (enLinea) "EN LÍNEA" else "SIN CONEXIÓN"}\n")
            append("Puerta: ${snap.getString("puerta") ?: "-"}\n")
            append("Accesos concedidos: ${snap.getLong("accesos") ?: 0}\n")
            append("Accesos denegados: ${snap.getLong("denegados") ?: 0}\n")
            append("Último método: ${snap.getString("ultimoMetodo").orEmpty().ifEmpty { "-" }}\n")
            append("Último UID: ${snap.getString("ultimoUid").orEmpty().ifEmpty { "-" }}\n")
            append("Tarjetas: ${snap.getLong("tarjetas") ?: 0} · Claves: ${snap.getLong("claves") ?: 0}\n")
            append("Señal WiFi: ${snap.getLong("rssi") ?: 0} dBm")
            if (snap.metadata.isFromCache) {
                append("\n(Datos guardados en el teléfono; sin conexión con Firebase)")
            }
        }
    }

    // ---------------------- COMANDOS ----------------------

    private fun comandoTarjeta(cmd: String) {
        val uid = binding.etUid.text.toString().trim()
        if (!regexUid.matches(uid)) {
            binding.tvMensaje.text = "El UID debe tener 8 caracteres hexadecimales"; return
        }
        enviarComando(cmd, mapOf("uid" to uid.uppercase()))
    }

    private fun comandoClave(cmd: String) {
        val clave = binding.etClaveNueva.text.toString().trim()
        if (!regexClave.matches(clave)) {
            binding.tvMensaje.text = "La clave debe tener entre 4 y 8 dígitos"; return
        }
        enviarComando(cmd, mapOf("clave" to clave))
    }

    private fun generarClave() {
        val clave = (SecureRandom().nextInt(900000) + 100000).toString()
        binding.tvClaveGenerada.text = clave
        enviarComando("add_clave", mapOf("clave" to clave))
    }

    private fun enviarComando(cmd: String, extra: Map<String, Any> = emptyMap()) {
        val miUid = FirebaseAuth.getInstance().currentUser?.uid
        if (miUid == null) { sesionExpirada(); return }

        val datos = hashMapOf<String, Any>(
            "cmd" to cmd,
            "estado" to "pendiente",
            "creadoPor" to miUid,
            "creadoEn" to FieldValue.serverTimestamp()
        )
        datos.putAll(extra)

        binding.tvMensaje.text = "Enviando '$cmd'..."
        // Si no hay Internet, Firestore guarda el comando en el teléfono y lo envía al reconectar
        devRef.collection("comandos").add(datos)
            .addOnSuccessListener { _binding?.tvMensaje?.text = "Comando '$cmd' enviado al dispositivo" }
            .addOnFailureListener { e ->
                _binding?.tvMensaje?.text = "No se pudo enviar el comando: ${e.localizedMessage}"
            }
    }

    // ---------------------- SESIÓN ----------------------

    private fun cerrarSesion() {
        FirebaseAuth.getInstance().signOut()
        sesion.cerrar()
        findNavController().navigate(R.id.action_global_loginFragment)
    }

    private fun sesionExpirada() {
        FirebaseAuth.getInstance().signOut()
        sesion.cerrar()
        findNavController().navigate(R.id.action_global_loginFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
