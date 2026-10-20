#=============================================================================
# SHIMSET handling - custom layer   (TURN operations only)
#  - SHIMSET ops: no spindle start, no coolant on, no G96/G961, G94 feed
#  - Other TURN ops: spindle + coolant ON at start, OFF + G94 at end
#  - TURN ops: first move at F200 (called from the FIRST_MOVE_TURN prepend)
#  - Every op (MILL + TURN) except SHIMSET: L_M54 once at end of operation
#    (removed from spindle stop, so mid-op M0 stops don't send it home)
#  - GAGECUT ops (not SHIMSET): wear offset tolerance check at the very end
#    of the operation, tolerances from the torna_offset_kontrol UDE
#      TURN: X and Z wear (tolerances from the UDE)
#      MILL: radius and Z (length) wear, fixed tolerance 0.004
#  - TURN ops: safety lines (ROT / G90 G94 G40 G700) at start of every operation
#  - All ops: coolant UDE effects reset at the end of each operation
#  - All ops: mom_spindle_direction reset to CLW at the end of each operation
#  - All ops: Fanuc-style Insert text (M00(MSG,...), (comment)) -> 840D syntax
#  - Tapping ops: CYCLE832 deselected (CYCLE832(0,0,1)) before CYCLE84 and
#    the previous CYCLE832 restored right after the cycle
#  - GRUP ... GRUPSON: items run continuously (one tool, no tool change, no
#    laser, no M9 M5 / home / L_M54 between items), checks with MOM_abort
#  - Other than the safety lines, non-TURN ops are left to the library
#=============================================================================

#-----------------------------------------------------------------------------
# Helpers
#-----------------------------------------------------------------------------
proc SHIMSET_is_turn {} {
  global mom_machine_mode
  return [expr {[info exists mom_machine_mode] && \
                [string equal -nocase $mom_machine_mode "TURN"]}]
}

proc get_next_oper_name {} {
  global mom_operation_name mom_operation_name_list
  if {![info exists mom_operation_name_list]} { return "" }
  set ops [regexp -all -inline {\S+} $mom_operation_name_list]
  set i [lsearch -exact $ops $mom_operation_name]
  if {$i < 0 || $i + 1 >= [llength $ops]} { return "" }
  return [lindex $ops [expr {$i + 1}]]
}

proc SHIMSET_start {} {
  global mom_operation_name shimset_mode shimset_turn_op shimset_g94_done
  set shimset_g94_done 0
  set ::tap_hsm_off_done 0
  set ::tap_hsm_restored 0
  TURN_first_move_restore
  GAGE_reset
  set shimset_turn_op [SHIMSET_is_turn]
  set shimset_mode [expr {$shimset_turn_op && \
                    [string match -nocase "*SHIMSET*" $mom_operation_name]}]
}

proc STOP_spindle_coolant {} {
  SHIMSET_orig_LIB_WRITE_coolant off   ;# original, bypasses shimset wrapper
  LIB_SPINDLE_end
  # Spindle is stopped: leave G96/G95 (feed per rev) so axes can still move
  MOM_output_literal "G94"
}

#-----------------------------------------------------------------------------
# Safety lines at the start of every TURN operation (one block per list entry)
# (milling uses its own safety routine)
#  ROT     cancel programmable frames (akilli AROT, any leftover TRANS/ROT)
#          - frame commands need their own block
#  G90     absolute
#  G94     feed per minute; also cancels G96/G961 (same G group - do NOT
#          put G97 in the same block)
#  G40     cancel cutter / tool nose radius compensation
#  G700    inch, incl. feeds
#  TRAFOOF / CYCLE800() are deliberately NOT here: the library may keep a
#  swivel or TRAORI active across operations and not re-output it.
#-----------------------------------------------------------------------------
set TURN_SAFETY_LINES {"ROT" "G90 G94 G40 G700"}

# Words kept out of SHIMSET operations (disabled for the whole operation).
# G_spin = the word that prints G96/G961 - check the name in the
# initial_move_turn / linear_move_turn templates if G96 still appears.
set SHIMSET_BLOCK_ADDRS {M_coolant G_spin}

#-----------------------------------------------------------------------------
# Coolant UDE reset (all operations)
#  A coolant UDE (e.g. Coolant OFF) changes mom_coolant_* and NX keeps those
#  values for every following operation -> every later operation printed M9
#  instead of M8. The mom_coolant_* values are snapshotted at the first
#  start of path (= no UDE yet) and restored at the end of every operation,
#  so a coolant UDE only affects its own operation.
#-----------------------------------------------------------------------------
set COOL_snapshot ""

# Spindle direction every operation starts from (reset at end of each one)
set SPINDLE_DEFAULT_DIR CLW

proc COOL_vars {} {
  set out {}
  foreach v [info globals mom_coolant*] {
    if {[array exists ::$v]} { continue }
    lappend out $v
  }
  return $out
}

proc COOL_take_snapshot {} {
  global COOL_snapshot
  if {$COOL_snapshot ne ""} { return }
  set COOL_snapshot [dict create]
  foreach v [COOL_vars] { dict set COOL_snapshot $v [set ::$v] }
  dict set COOL_snapshot __taken 1
}

proc COOL_restore {} {
  global COOL_snapshot
  if {$COOL_snapshot eq ""} { return }
  foreach v [COOL_vars] {
    if {[dict exists $COOL_snapshot $v]} {
      set ::$v [dict get $COOL_snapshot $v]
    } else {
      unset -nocomplain ::$v          ;# created by a UDE -> remove again
    }
  }
}

#-----------------------------------------------------------------------------
# Event wrappers (each event wrapped ONCE)
#-----------------------------------------------------------------------------
if {![llength [info commands SHIMSET_orig_start_of_path]]} {
  rename MOM_start_of_path SHIMSET_orig_start_of_path
  proc MOM_start_of_path {} {
    global shimset_mode shimset_turn_op
    COOL_take_snapshot          ;# first operation only: coolant defaults
    SHIMSET_start
    GRUP_install                ;# wrap Hermle_Custom_Toolchange (once)
    GRUP_start                  ;# group state for this item

    if {$shimset_mode} {
      # Block words from ANY template during shimset (e.g. initial_move_turn):
      #   M_coolant - no M8
      #   G_spin    - no G96/G961 (would switch to feed per rev -> stall)
      global SHIMSET_BLOCK_ADDRS
      foreach a $SHIMSET_BLOCK_ADDRS { catch {MOM_disable_address $a} }
    } elseif {$shimset_turn_op} {
      # Guarantee M8 on the first coolant call, even if M_coolant looks modal
      MOM_force once M_coolant
      # Literal G94 bypassed the library's modal tracking -> re-output G96/G961
      MOM_force once G_spin
    }
    SHIMSET_orig_start_of_path

    # Safety lines at the start of every TURN operation (incl. SHIMSET)
    # (milling has its own safety routine)
    if {$shimset_turn_op} {
      global TURN_SAFETY_LINES
      foreach l $TURN_SAFETY_LINES { MOM_output_literal $l }
    }

    # GRUP: our state wins over any other grup_flag setter at start of path
    GRUP_assert_flag
    global grup_flag mom_operation_name
    if {$grup_flag} { MOM_output_literal ";*** $mom_operation_name ***" }
  }
}

if {![llength [info commands SHIMSET_orig_end_of_path]]} {
  rename MOM_end_of_path SHIMSET_orig_end_of_path
  proc MOM_end_of_path {} {
    global mom_next_oper_has_tool_change shimset_mode shimset_turn_op
    global grup_devam

    # GRUP checks (akilli / tool change / not closed) - abort before output
    GRUP_end_check

    # Tapping op: CYCLE832 back on BEFORE any end-of-path output
    # (fallback if MOM_cycle_off didn't already do it)
    TAP_hsm_restore

    SHIMSET_orig_end_of_path

    if {$shimset_mode} {
      global SHIMSET_BLOCK_ADDRS
      foreach a $SHIMSET_BLOCK_ADDRS { catch {MOM_enable_address $a} }
    }

    # Library already stops spindle/coolant on tool/MCS change.
    # Otherwise stop them ourselves after every cutting TURN op.
    set tc [expr {[info exists mom_next_oper_has_tool_change] \
                  ? $mom_next_oper_has_tool_change : "YES"}]
    # Not inside a group: spindle and coolant keep running to the next item
    if {$shimset_turn_op && !$shimset_mode && $tc eq "NO" && !$grup_devam} {
      STOP_spindle_coolant
    }

    # Home / reset at the end of every operation (after M9/M5)
    # - not after SHIMSET ops (tool stays at the part for the following cut)
    # - not between the items of a group (only after the GRUPSON item)
    if {!$shimset_mode && !$grup_devam} {
      MOM_output_literal "L_M54"
    }

    # Wear offset tolerance check - GAGECUT ops (TURN and MILL), not SHIMSET,
    # after everything else
    global mom_operation_name
    if {![string match -nocase "*SHIMSET*" $mom_operation_name] && \
        [string match -nocase "*GAGECUT*" $mom_operation_name]} {
      GAGE_output_wear_check [expr {$shimset_turn_op ? "TURN" : "MILL"}]
    }

    # Coolant UDE effects end with their operation
    COOL_restore

    # Spindle direction back to the default for the next operation: an M4 /
    # CCLW (e.g. from a spindle UDE) must not stick to later operations.
    # An operation that sets its own direction still overrides this, because
    # NX sends its spindle event after start of path.
    global SPINDLE_DEFAULT_DIR
    set ::mom_spindle_direction $SPINDLE_DEFAULT_DIR

    set shimset_mode    0
    set shimset_turn_op 0
    GRUP_end_done
  }
}

#-----------------------------------------------------------------------------
# Library proc wrappers (only act when shimset_mode = 1, i.e. TURN + SHIMSET)
#-----------------------------------------------------------------------------
if {![llength [info commands SHIMSET_orig_LIB_SPINDLE_start]]} {
  rename LIB_SPINDLE_start SHIMSET_orig_LIB_SPINDLE_start
  proc LIB_SPINDLE_start {args} {
    global shimset_mode shimset_g94_done
    if {[info exists shimset_mode] && $shimset_mode} {
      # Library calls this on every move -> output G94 only once per op
      if {![info exists shimset_g94_done] || !$shimset_g94_done} {
        MOM_output_literal "G94"
        set shimset_g94_done 1
      }
      return
    }
    return [uplevel 1 [list SHIMSET_orig_LIB_SPINDLE_start {*}$args]]
  }
}

if {![llength [info commands SHIMSET_orig_LIB_WRITE_coolant]]} {
  rename LIB_WRITE_coolant SHIMSET_orig_LIB_WRITE_coolant
  proc LIB_WRITE_coolant {args} {
    global shimset_mode
    TURN_first_move_restore   ;# undo F200 override right after the first move
    if {[info exists shimset_mode] && $shimset_mode \
        && [lindex $args 0] eq "on"} {
      return
    }
    return [uplevel 1 [list SHIMSET_orig_LIB_WRITE_coolant {*}$args]]
  }
}

#-----------------------------------------------------------------------------
# TURN: first/initial move of every operation at F200.
# Called from your  LIB_GE_command_buffer_edit_prepend MOM_linear_move_LIB
# FIRST_MOVE_TURN  block (runs right before the first move is written).
#
# We don't know which variable the F word reads, so: every global with
# "feed" in its name that currently holds the first move's feed value
# (e.g. 30) is set to 200, then restored right after the move is written
# (in the COOLANT_ON step that follows FIRST_MOVE_TURN).
# TURN_FEED_DEBUG 1 writes a comment listing the variables it changed.
#-----------------------------------------------------------------------------
set TURN_FIRST_MOVE_FEED 200.0
set TURN_FEED_DEBUG 0
set turn_feed_saved {}

proc TURN_first_move_feed {} {
  global mom_feed_rate TURN_FIRST_MOVE_FEED TURN_FEED_DEBUG turn_feed_saved
  TURN_first_move_restore
  if {![info exists mom_feed_rate] || ![string is double -strict $mom_feed_rate]} {
    return
  }
  set orig $mom_feed_rate
  set changed {}
  foreach v [info globals] {
    if {![string match -nocase "*feed*" $v]} { continue }
    upvar #0 $v val
    if {[array exists val] || ![info exists val]} { continue }
    if {![string is double -strict $val]} { continue }
    if {abs($val - $orig) > 1e-6} { continue }
    dict set turn_feed_saved $v $val
    set val $TURN_FIRST_MOVE_FEED
    lappend changed $v
  }
  MOM_force once F
  if {$TURN_FEED_DEBUG} {
    MOM_output_literal ";F200 DEBUG orig=$orig vars=$changed"
  }
}

proc TURN_first_move_restore {} {
  global turn_feed_saved
  if {![info exists turn_feed_saved]} { return }
  dict for {v val} $turn_feed_saved {
    upvar #0 $v ref
    set ref $val
  }
  set turn_feed_saved {}
}

#-----------------------------------------------------------------------------
# GAGECUT: wear offset tolerance check
#  Tolerances come from the torna_offset_kontrol UDE:
#    mom_t_offset_kontrol_tolerans_X / _Z   (_tip / _olcum ignored)
#  The UDE fires mid-operation, so its values are captured there and the
#  check is written at end of path.
#
#  !! VERIFY on the machine: which wear parameter holds X and Z for a
#     turning tool in G18 on this Hermle (default SINUMERIK: L1 wear =
#     $TC_DP12, L2 wear = $TC_DP13). Change GAGE_WEAR_DP_X/Z if needed.
#-----------------------------------------------------------------------------
set GAGE_WEAR_DP_X 12
set GAGE_WEAR_DP_Z 13
set gage_label_count 0

# MILL (G17 milling tool): length 1 wear = Z, radius wear
#   SINUMERIK: $TC_DP12 = wear length 1, $TC_DP15 = wear radius
# Tolerances: fixed values, no UDE (always checked in MILL GAGECUT ops)
set GAGE_MILL_DP_Z 12
set GAGE_MILL_DP_R 15
set GAGE_MILL_TOL_R 0.004
set GAGE_MILL_TOL_Z 0.004

proc GAGE_reset {} {
  global gage_tol_x gage_tol_z
  set gage_tol_x ""
  set gage_tol_z ""
}

proc GAGE_capture_ude {} {
  global gage_tol_x gage_tol_z
  global mom_t_offset_kontrol_tolerans_X mom_t_offset_kontrol_tolerans_Z
  if {[info exists mom_t_offset_kontrol_tolerans_X]} {
    set gage_tol_x $mom_t_offset_kontrol_tolerans_X
  }
  if {[info exists mom_t_offset_kontrol_tolerans_Z]} {
    set gage_tol_z $mom_t_offset_kontrol_tolerans_Z
  }
}

# The UDE event currently has no handler ("MISSING EVENT HANDLER"),
# so define it - or wrap it if the post gets one later.
if {[llength [info commands MOM_torna_offset_kontrol]]} {
  if {![llength [info commands GAGE_orig_torna_offset_kontrol]]} {
    rename MOM_torna_offset_kontrol GAGE_orig_torna_offset_kontrol
    proc MOM_torna_offset_kontrol {} {
      GAGE_capture_ude
      GAGE_orig_torna_offset_kontrol
    }
  }
} else {
  proc MOM_torna_offset_kontrol {} { GAGE_capture_ude }
}

# One axis: loop until the wear offset is within tolerance.
# Operator sees the message, fixes the wear value, presses Cycle Start.
proc GAGE_output_axis {axis dp tol} {
  global gage_label_count
  if {$tol eq "" || ![string is double -strict $tol] || $tol <= 0.0} { return }

  incr gage_label_count
  set lbl "WCHK${axis}${gage_label_count}"
  set t   [format "%.4f" $tol]
  set var [format {$TC_DP%d[$P_TOOLNO,$P_TOOL]} $dp]

  MOM_output_literal "${lbl}:"
  MOM_output_literal "IF ABS(${var})>${t}"
  set name [expr {$axis eq "R" ? "RADYUS" : $axis}]
  MOM_output_literal "MSG(\"${name} ASINMA OFSETI TOLERANS DISI (+/-${t}) - KONTROL ET\")"
  MOM_output_literal "M0"
  MOM_output_literal "GOTOB ${lbl}"
  MOM_output_literal "ENDIF"
}

proc GAGE_output_wear_check {{mode TURN}} {
  global gage_tol_x gage_tol_z GAGE_WEAR_DP_X GAGE_WEAR_DP_Z
  global GAGE_MILL_DP_Z GAGE_MILL_DP_R GAGE_MILL_TOL_R GAGE_MILL_TOL_Z

  if {$mode eq "MILL"} {
    MOM_output_literal "M01"
    MOM_output_literal ";*** ASINMA OFSET TOLERANS KONTROLU ***"
    GAGE_output_axis R $GAGE_MILL_DP_R $GAGE_MILL_TOL_R
    GAGE_output_axis Z $GAGE_MILL_DP_Z $GAGE_MILL_TOL_Z
  } else {
    if {$gage_tol_x eq "" && $gage_tol_z eq ""} { return }   ;# no UDE in this op
    MOM_output_literal "M01"
    MOM_output_literal ";*** ASINMA OFSET TOLERANS KONTROLU ***"
    GAGE_output_axis X $GAGE_WEAR_DP_X $gage_tol_x
    GAGE_output_axis Z $GAGE_WEAR_DP_Z $gage_tol_z
  }
  MOM_output_literal "MSG()"
}

#-----------------------------------------------------------------------------
# INSERT (MOM_insert) text -> SINUMERIK 840D sl syntax   (all operations)
#  Programmers type Fanuc-style text in NX "Insert" commands. Convert:
#    M00(MSG,text) / M0(MSG,text)  ->  MSG("text") / M0 / MSG()
#    M01(MSG,text) / M1(MSG,text)  ->  MSG("text") / M1 / MSG()
#    (MSG,text)                    ->  MSG("text")
#    (any comment)                 ->  ; any comment
#  Anything else is passed through unchanged.
#-----------------------------------------------------------------------------
proc INSERT_to_840d {txt} {
  set t [string trim $txt]

  # M0 / M00 / M1 / M01 followed by (MSG, ...)
  if {[regexp -nocase {^M0*([01])\s*\(\s*MSG\s*,\s*(.*)\)\s*$} $t -> mcode msg]} {
    set msg [string map {"\"" "'"} [string trim $msg]]
    return [list "MSG(\"$msg\")" "M$mcode" "MSG()"]
  }
  # (MSG, ...) without stop -> message only
  if {[regexp -nocase {^\(\s*MSG\s*,\s*(.*)\)\s*$} $t -> msg]} {
    set msg [string map {"\"" "'"} [string trim $msg]]
    return [list "MSG(\"$msg\")"]
  }
  # Plain Fanuc comment (text) -> ; text
  if {[regexp {^\((.*)\)$} $t -> cmt]} {
    return [list "; [string trim $cmt]"]
  }
  return {}   ;# no conversion
}

if {[llength [info commands MOM_insert]] && \
    ![llength [info commands INSERT_orig_MOM_insert]]} {
  rename MOM_insert INSERT_orig_MOM_insert
  proc MOM_insert {} {
    global mom_Instruction
    if {[info exists mom_Instruction]} {
      set lines [INSERT_to_840d $mom_Instruction]
      if {[llength $lines]} {
        foreach l $lines { MOM_output_literal $l }
        return
      }
    }
    INSERT_orig_MOM_insert
  }
}

#-----------------------------------------------------------------------------
# TAPPING: deselect CYCLE832 (high speed settings) before CYCLE84
#  CYCLE832 compressor/smoothing active during rigid tapping makes the
#  spindle/Z synchronisation misbehave. Output the deselect once per
#  tapping operation, right before the first tap cycle block (MCALL CYCLE84).
#
#  Restore: right after the cycle is cancelled (MOM_cycle_off, fallback at
#  the start of end of path) the last CYCLE832 the post wrote is output
#  again, so the following items (e.g. the rest of a GRUP, where CYCLE832
#  may not be written again) don't run with CYCLE832 off.
#  Otherwise the restore is a harmless repeat.
#
#  TAP_HSM_OFF      deselect call (_TOLM = 0 -> off)
#  TAP_HSM_DEFAULT  used for the restore if no CYCLE832 was seen yet
#                   ("" = write a warning comment instead)
#-----------------------------------------------------------------------------
set TAP_HSM_OFF "CYCLE832(0,0,1)"
set TAP_HSM_DEFAULT ""
set tap_hsm_off_done 0
set tap_hsm_restored 0
set tap_hsm_last ""

# NX cycle-parameter events for tapping (fire before the cycle block)
set TAP_EVENTS {MOM_tap MOM_tap_float MOM_tap_deep MOM_tap_break_chip}

# Remember the last CYCLE832 select written (literal or template output)
proc TAP_hsm_capture {txt} {
  global TAP_HSM_OFF tap_hsm_last
  if {![regexp {CYCLE832\s*\([^)]*\)} $txt call]} { return }
  if {[string map {" " ""} $call] eq $TAP_HSM_OFF} { return }
  if {[regexp {CYCLE832\s*\(\s*[^,]*,\s*0\s*,} $call]} { return }  ;# any deselect
  set tap_hsm_last $call
}

proc TAP_hsm_off {} {
  global TAP_HSM_OFF tap_hsm_off_done
  if {[info exists tap_hsm_off_done] && $tap_hsm_off_done} { return }
  MOM_output_literal $TAP_HSM_OFF
  set tap_hsm_off_done 1
}

proc TAP_hsm_restore {} {
  global tap_hsm_off_done tap_hsm_restored tap_hsm_last TAP_HSM_DEFAULT
  if {![info exists tap_hsm_off_done] || !$tap_hsm_off_done} { return }
  if {[info exists tap_hsm_restored] && $tap_hsm_restored} { return }
  set tap_hsm_restored 1
  if {$tap_hsm_last ne ""} {
    MOM_output_literal $tap_hsm_last
  } elseif {$TAP_HSM_DEFAULT ne ""} {
    MOM_output_literal $TAP_HSM_DEFAULT
  } else {
    MOM_output_literal "; !! CYCLE832 RESTORE: NO PREVIOUS CYCLE832 FOUND"
  }
}

foreach ev $TAP_EVENTS {
  if {[llength [info commands $ev]] && \
      ![llength [info commands TAP_orig_$ev]]} {
    rename $ev TAP_orig_$ev
    proc $ev {} [format {
      TAP_hsm_off
      uplevel 1 TAP_orig_%s
    } $ev]
  }
}

# Restore right after the cycle cancel (MCALL) - still inside the op
if {[llength [info commands MOM_cycle_off]] && \
    ![llength [info commands TAP_orig_MOM_cycle_off]]} {
  rename MOM_cycle_off TAP_orig_MOM_cycle_off
  proc MOM_cycle_off {} {
    uplevel 1 TAP_orig_MOM_cycle_off
    TAP_hsm_restore
  }
}

# Capture every CYCLE832 the post writes
if {![llength [info commands TAP_orig_MOM_output_literal]]} {
  rename MOM_output_literal TAP_orig_MOM_output_literal
  proc MOM_output_literal {txt} {
    TAP_hsm_capture $txt
    return [TAP_orig_MOM_output_literal $txt]
  }
}
if {![llength [info commands TAP_orig_MOM_do_template]]} {
  rename MOM_do_template TAP_orig_MOM_do_template
  proc MOM_do_template {args} {
    set r [uplevel 1 [linsert $args 0 TAP_orig_MOM_do_template]]
    if {[lindex $args 1] ne "CREATE"} { TAP_hsm_capture $r }
    return $r
  }
}

#-----------------------------------------------------------------------------
# GRUP / GRUPSON
#  An operation name containing GRUP opens a group, the next one containing
#  GRUPSON closes it (both items belong to the group). Inside a group the
#  program runs like a normal post:
#   - one tool for the whole group: no tool change, no laser check after the
#     GRUP item (Hermle_Custom_Toolchange is skipped, your laser proc
#     returns on grup_flag)
#   - spindle and coolant stay on: no M9 M5 / return home / L_M54 between
#     items - those come once, at the end of the GRUPSON item
#   - every following item gets its operation name as a comment
#
#  State variables (use them in your own code):
#   grup_flag    1 = item continues a group (every item after the GRUP item)
#                    -> skip tool change, laser, item-start stuff
#   grup_devam   1 = the group continues after this item (every item before
#                    the GRUPSON item) -> skip M9 M5 / home / L_M54 at its end
#   grup_icinde  1 = item is part of a group (any position)
#
#  Errors (post stops with MOM_abort):
#   - GRUPSON without an open GRUP, or a new GRUP before GRUPSON
#   - akilli in a group item
#   - tool change inside a group
#   - group not closed (last operation has no GRUPSON)
#-----------------------------------------------------------------------------
set GRUP_KEY      "GRUP"
set GRUPSON_KEY   "GRUPSON"
set grup_open     0
set grup_flag     0
set grup_devam    0
set grup_icinde   0
set grup_start_op ""

proc GRUP_abort {msg} {
  set s "\n\n******************************   U Y A R I   ******************************\n\n"
  MOM_abort "$s   $msg \n$s"
}

# Start of path: where is this item in a group?
proc GRUP_start {} {
  global mom_operation_name GRUP_KEY GRUPSON_KEY
  global grup_open grup_flag grup_devam grup_icinde grup_start_op

  set n [string toupper $mom_operation_name]
  set is_end   [expr {[string first $GRUPSON_KEY $n] >= 0}]
  set is_begin [expr {!$is_end && [string first $GRUP_KEY $n] >= 0}]

  if {$is_end && !$grup_open} {
    GRUP_abort "GRUPSON var ama acik GRUP yok. Ilgili Item: ( $mom_operation_name )"
  }
  if {$is_begin && $grup_open} {
    GRUP_abort "GRUP ( $grup_start_op ) GRUPSON ile kapanmadan yeni GRUP basladi. Ilgili Item: ( $mom_operation_name )"
  }
  if {$is_begin} {
    set grup_open 1
    set grup_start_op $mom_operation_name
  }

  set grup_icinde $grup_open
  set grup_flag   [expr {$grup_open && !$is_begin}]
  set grup_devam  [expr {$grup_open && !$is_end}]
  set ::grup_flag_mine $grup_flag
}

# After the library start of path: our grup_flag wins
proc GRUP_assert_flag {} {
  if {[info exists ::grup_flag_mine]} { set ::grup_flag $::grup_flag_mine }
}

# End of path, before any output
proc GRUP_end_check {} {
  global grup_icinde grup_devam grup_start_op mom_operation_name
  global mom_next_oper_has_tool_change
  # (akilli is checked by the trace on mom_akilli, the moment the UDE sets it)
  if {![info exists grup_icinde] || !$grup_icinde || !$grup_devam} { return }

  if {[info exists ::mom_operation_name_list] && [get_next_oper_name] eq ""} {
    GRUP_abort "GRUP ( $grup_start_op ) GRUPSON ile kapanmamis. Son Item: ( $mom_operation_name )"
  }
  if {[info exists mom_next_oper_has_tool_change] && \
      $mom_next_oper_has_tool_change eq "YES"} {
    GRUP_abort "GRUP icinde takim degisemez. GRUP: ( $grup_start_op )  Ilgili Item: ( [get_next_oper_name] )"
  }
}

# End of path, after everything: GRUPSON item closes the group
proc GRUP_end_done {} {
  global grup_icinde grup_devam grup_open
  if {$grup_icinde && !$grup_devam} { set grup_open 0 }
}

# Akilli UDE inside a group -> stop right away (UDE fires after start of path)
proc GRUP_akilli_trace {args} {
  global grup_icinde mom_akilli mom_operation_name
  if {![info exists grup_icinde] || !$grup_icinde} { return }
  if {[info exists mom_akilli] && [string is true -strict $mom_akilli]} {
    GRUP_abort "GRUP ve AKILLI ayni anda kullanilamaz. Ilgili Item: ( $mom_operation_name )"
  }
}
if {[lsearch -exact [trace info variable ::mom_akilli] {write GRUP_akilli_trace}] < 0} {
  trace add variable ::mom_akilli write GRUP_akilli_trace
}

# No tool change for the items after the GRUP item. Wrapped at the first
# start of path, so it works whatever order the custom files are loaded in.
proc GRUP_install {} {
  if {![llength [info commands Hermle_Custom_Toolchange]]} { return }
  if {[llength [info commands GRUP_orig_Hermle_Custom_Toolchange]]} { return }
  rename Hermle_Custom_Toolchange GRUP_orig_Hermle_Custom_Toolchange
  proc Hermle_Custom_Toolchange {args} {
    global grup_flag
    if {[info exists grup_flag] && $grup_flag} { return }
    uplevel 1 [list GRUP_orig_Hermle_Custom_Toolchange {*}$args]
  }
}
