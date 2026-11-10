package com.example.eva_2

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.eva_2.databinding.FragmentLoginBinding
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Registro / inicio de sesión con Firebase Authentication.
 * El nombre y el rol (USUARIO o ADMIN) se guardan en Firestore: usuarios/{uid}.
 * Los ADMIN se asignan a mano en la consola de Firebase (campo "rol").
 */
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private lateinit var sesion: SessionManager
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var modoRegistro = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sesion = SessionManager(requireContext().applicationContext)

        binding.tvCambiarModo.setOnClickListener { cambiarModo(!modoRegistro) }
        binding.btnAccion.setOnClickListener { enviar() }

        val actual = auth.currentUser
        if (actual != null) {
            // Ya hay sesión de Firebase: se carga el perfil y se pasa al panel
            ocupado(true)
            cargarPerfilYContinuar(actual.uid)
        } else {
            sesion.cerrar() // limpia cualquier sesión local antigua
        }
    }

    private fun cambiarModo(registro: Boolean) {
        modoRegistro = registro
        binding.etNombre.visibility = if (registro) View.VISIBLE else View.GONE
        binding.btnAccion.setText(if (registro) R.string.btn_registrar else R.string.btn_iniciar)
        binding.tvCambiarModo.setText(if (registro) R.string.link_ir_login else R.string.link_ir_registro)
        binding.tvMensaje.text = ""
    }

    private fun enviar() {
        val nombre = binding.etNombre.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tvMensaje.text = "Correo electrónico inválido"; return
        }
        if (password.length < 8) {
            binding.tvMensaje.text = "La contraseña debe tener al menos 8 caracteres"; return
        }
        if (modoRegistro && nombre.length < 2) {
            binding.tvMensaje.text = "Ingresa tu nombre"; return
        }

        binding.tvMensaje.text = ""
        ocupado(true)

        if (modoRegistro) {
            auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { res ->
                    val uid = res.user?.uid
                    if (uid == null) { ocupado(false); return@addOnSuccessListener }
                    val perfil = hashMapOf<String, Any>(
                        "nombre" to nombre,
                        "email" to email,
                        "rol" to "USUARIO",
                        "creadoEn" to FieldValue.serverTimestamp()
                    )
                    db.collection("usuarios").document(uid).set(perfil)
                        .addOnSuccessListener {
                            sesion.guardar(uid, nombre, "USUARIO")
                            ocupado(false)
                            irAlPanel()
                        }
                        .addOnFailureListener { e -> mostrarError(e) }
                }
                .addOnFailureListener { e -> mostrarError(e) }
        } else {
            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener { res -> cargarPerfilYContinuar(res.user?.uid) }
                .addOnFailureListener { e -> mostrarError(e) }
        }
    }

    private fun cargarPerfilYContinuar(uid: String?) {
        if (uid == null) { ocupado(false); return }
        db.collection("usuarios").document(uid).get()
            .addOnSuccessListener { d ->
                val nombre = d.getString("nombre") ?: auth.currentUser?.email.orEmpty()
                val rol = d.getString("rol") ?: "USUARIO"
                sesion.guardar(uid, nombre, rol)
                ocupado(false)
                irAlPanel()
            }
            .addOnFailureListener {
                ocupado(false)
                if (sesion.token == uid) {
                    irAlPanel() // sin conexión: se usa el perfil guardado en el teléfono
                } else {
                    _binding?.tvMensaje?.setText(R.string.error_red)
                }
            }
    }

    private fun ocupado(si: Boolean) {
        val b = _binding ?: return
        b.progreso.visibility = if (si) View.VISIBLE else View.GONE
        b.btnAccion.isEnabled = !si
    }

    private fun mostrarError(e: Exception) {
        ocupado(false)
        val b = _binding ?: return
        b.etPassword.text?.clear()
        b.tvMensaje.text = when (e) {
            is FirebaseNetworkException -> getString(R.string.error_red)
            is FirebaseAuthInvalidCredentialsException,
            is FirebaseAuthInvalidUserException -> "Correo o contraseña incorrectos"
            is FirebaseAuthUserCollisionException -> "Ese correo ya está registrado"
            is FirebaseAuthWeakPasswordException -> "La contraseña es demasiado débil"
            else -> e.localizedMessage ?: "Error desconocido"
        }
    }

    private fun irAlPanel() {
        if (_binding == null || !isAdded) return
        binding.etPassword.text?.clear()
        findNavController().navigate(R.id.action_loginFragment_to_panelFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
