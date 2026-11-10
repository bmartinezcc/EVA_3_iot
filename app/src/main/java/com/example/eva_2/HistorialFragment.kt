package com.example.eva_2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.example.eva_2.databinding.FragmentHistorialBinding
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Locale

/** Historial de intentos de acceso guardados en Firestore (últimos 50). */
class HistorialFragment : Fragment() {

    private var _binding: FragmentHistorialBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistorialBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val formato = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())

        FirebaseFirestore.getInstance()
            .collection("dispositivos").document(Config.DISPOSITIVO_ID)
            .collection("eventos")
            .orderBy("recibido", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { resultado ->
                val b = _binding ?: return@addOnSuccessListener
                b.progreso.visibility = View.GONE
                val lineas = resultado.documents.map { d ->
                    val fecha = d.getTimestamp("recibido")?.toDate()?.let { formato.format(it) } ?: "-"
                    val concedido = d.getBoolean("ok") == true
                    buildString {
                        append(fecha).append("\n")
                        append(if (concedido) "CONCEDIDO" else "DENEGADO")
                        append(" · ").append(d.getString("metodo").orEmpty())
                        val detalle = d.getString("detalle").orEmpty()
                        if (detalle.isNotEmpty()) append(" · ").append(detalle)
                        val uid = d.getString("uid").orEmpty()
                        if (uid.isNotEmpty()) append(" · ").append(uid)
                    }
                }
                if (lineas.isEmpty()) b.tvVacio.visibility = View.VISIBLE
                b.listaEventos.adapter =
                    ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, lineas)
            }
            .addOnFailureListener { e ->
                val b = _binding ?: return@addOnFailureListener
                b.progreso.visibility = View.GONE
                b.tvVacio.text = e.localizedMessage ?: getString(R.string.error_red)
                b.tvVacio.visibility = View.VISIBLE
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
