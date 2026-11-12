/// Hora actual del reloj del teléfono, en milisegundos desde 1970.
typedef NowMs = int Function();

int systemNowMs() => DateTime.now().millisecondsSinceEpoch;
