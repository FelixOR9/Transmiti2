import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

/// Pinta el reloj del partido y lo mantiene al día.
///
/// El tiempo NO se acumula aquí: en cada repintado se vuelve a calcular con la
/// hora real a partir del estado guardado ([MatchSession.reading]). Por eso,
/// si la app se minimiza, el teléfono se bloquea o Flutter reconstruye la
/// pantalla, el reloj siempre muestra el tiempo correcto. El temporizador
/// ([tick]) solo sirve para repintar y solo corre mientras el reloj avanza.
class LiveClock extends StatefulWidget {
  const LiveClock({
    super.key,
    required this.session,
    required this.tick,
    required this.builder,
  });

  final MatchSession session;

  /// Intervalo de repintado; nulo lo desactiva.
  final Duration? tick;
  final Widget Function(BuildContext context, ClockReading reading) builder;

  @override
  State<LiveClock> createState() => _LiveClockState();
}

class _LiveClockState extends State<LiveClock> with WidgetsBindingObserver {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.session.addListener(_onSessionChanged);
    _syncTimer();
  }

  @override
  void didUpdateWidget(LiveClock oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.session != widget.session) {
      oldWidget.session.removeListener(_onSessionChanged);
      widget.session.addListener(_onSessionChanged);
    }
    if (oldWidget.tick != widget.tick) {
      _timer?.cancel();
      _timer = null;
    }
    _syncTimer();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    widget.session.removeListener(_onSessionChanged);
    _timer?.cancel();
    super.dispose();
  }

  /// Al volver a la app (por ejemplo tras bloquear el teléfono) se repinta
  /// de inmediato con el tiempo real transcurrido.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && mounted) {
      setState(() {});
      _syncTimer();
    }
  }

  void _onSessionChanged() {
    if (!mounted) {
      return;
    }
    setState(() {});
    _syncTimer();
  }

  void _syncTimer() {
    final Duration? tick = widget.tick;
    final MatchClockState clock = widget.session.clock;
    final bool needsTimer =
        tick != null && (clock.running || clock.phase == MatchPhase.halfTime);
    if (needsTimer && _timer == null) {
      _timer = Timer.periodic(tick, (Timer timer) {
        if (mounted) {
          setState(() {});
        }
      });
    } else if (!needsTimer) {
      _timer?.cancel();
      _timer = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    return widget.builder(context, widget.session.reading());
  }
}
