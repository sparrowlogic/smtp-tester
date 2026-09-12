package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.chaos.ChaosMonkey;
import com.sparrowlogic.smtptester.chaos.ChaosSettings;
import com.sparrowlogic.smtptester.chaos.ChaosView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Turns fault injection on and off while the server is running.
 *
 * <p>Runtime control rather than configuration-only because that is how the feature is actually
 * used: a suite runs its happy-path tests against a quiet server, switches chaos on for the ones
 * that assert retry behaviour, and switches it off again. Restarting a container between those
 * groups would be much slower and would lose the captured inbox.
 */
@RestController
@RequestMapping("${smtptester.web.base-path:}/api/v1/chaos")
public class ChaosController {

    private final ChaosMonkey chaos;

    public ChaosController(final ChaosMonkey chaos) {
        this.chaos = chaos;
    }

    /** The probabilities in force, plus how many faults have been injected. */
    @GetMapping
    public ResponseEntity<ChaosView> current() {
        return ResponseEntity.ok(ChaosView.of(this.chaos));
    }

    /** Replaces every probability at once. */
    @PutMapping
    public ResponseEntity<ChaosView> replace(@RequestBody final ChaosSettings settings) {
        this.chaos.reconfigure(settings);
        return ResponseEntity.ok(ChaosView.of(this.chaos));
    }

    /** Switches chaos on, leaving the configured probabilities alone. */
    @PostMapping("/enable")
    public ResponseEntity<ChaosView> enable() {
        this.chaos.setEnabled(true);
        return ResponseEntity.ok(ChaosView.of(this.chaos));
    }

    /** Switches chaos off. */
    @PostMapping("/disable")
    public ResponseEntity<ChaosView> disable() {
        this.chaos.setEnabled(false);
        return ResponseEntity.ok(ChaosView.of(this.chaos));
    }

    /** Zeroes the fault counters, so one test's numbers do not leak into the next. */
    @DeleteMapping("/faults")
    public ResponseEntity<ChaosView> resetCounters() {
        this.chaos.resetCounters();
        return ResponseEntity.ok(ChaosView.of(this.chaos));
    }
}
