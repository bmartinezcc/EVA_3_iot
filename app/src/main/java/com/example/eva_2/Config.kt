package com.example.eva_2

object Config {
    // Debe ser igual a ID_DISP del sketch del ESP32 y a la variable ID_DISP del puente
    const val DISPOSITIVO_ID = "rfidtrack-bm01"

    // Ya no se usa (era la API PHP de la Raspberry). Se deja solo para que
    // ApiClient.kt, si sigue en tu carpeta, no dé error de compilación.
    const val BASE_URL = "http://100.113.97.26/eva2iot"
}
