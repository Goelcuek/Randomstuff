#=============================================================================
# SHIMSET handling - custom layer   (TURN operations only)
#  - SHIMSET ops: no spindle start, no coolant on, G94 feed
#  - Other TURN ops: spindle + coolant ON at start, OFF + G94 at end
#  - TURN ops: first move at F200 (called from the FIRST_MOVE_TURN prepend)
#  - Every op (MILL + TURN) except SHIMSET: L_M54 once at end of operation
#    (removed from spindle stop, so mid-op M0 stops don't send it home)
#  - TURN GAGECUT ops (not SHIMSET): wear offset tolerance check at the very
#    end of the operation, tolerances from the torna_offset_kontrol UDE
#  - TURN ops: safety line (G90 G94 G40 G700) at start of every operation
#  - All ops: Fanuc-style Insert text (M00(MSG,...), (comment)) -> 840D syntax
#  - Anything with mom_machine_mode != TURN: library runs untouched
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
# Safety line for every TURN operation start
#  G90  absolute     G94  feed per minute (safe while spindle may be off)
#  G40  cancel tool nose radius comp     G700 inch, incl. feeds
#-----------------------------------------------------------------------------
set TURN_SAFETY_LINE "G90 G94 G40 G700"

#-----------------------------------------------------------------------------
# Event wrappers (each event wrapped ONCE)
#-----------------------------------------------------------------------------
if {![llength [info commands SHIMSET_orig_start_of_path]]} {
  rename MOM_start_of_path SHIMSET_orig_start_of_path
  proc MOM_start_of_path {} {
    global shimset_mode shimset_turn_op
    SHIMSET_start

    if {$shimset_mode} {
      # Block M8 from ANY template during shimset (e.g. initial_move_turn)
      MOM_disable_address M_coolant
    } elseif {$shimset_turn_op} {
      # Guarantee M8 on the first coolant call, even if M_coolant looks modal
      MOM_force once M_coolant
      # Literal G94 bypassed the library's modal tracking -> re-output G96/G961
      MOM_force once G_spin
    }
    SHIMSET_orig_start_of_path

    # Safety line at the start of every TURN operation (incl. SHIMSET)
    if {$shimset_turn_op} {
      global TURN_SAFETY_LINE
      MOM_output_literal $TURN_SAFETY_LINE
    }
  }
}

if {![llength [info commands SHIMSET_orig_end_of_path]]} {
  rename MOM_end_of_path SHIMSET_orig_end_of_path
  proc MOM_end_of_path {} {
    global mom_next_oper_has_tool_change shimset_mode shimset_turn_op

    SHIMSET_orig_end_of_path

    if {$shimset_mode} {
      MOM_enable_address M_coolant
    }

    # Library already stops spindle/coolant on tool/MCS change.
    # Otherwise stop them ourselves after every cutting TURN op.
    set tc [expr {[info exists mom_next_oper_has_tool_change] \
                  ? $mom_next_oper_has_tool_change : "YES"}]
    if {$shimset_turn_op && !$shimset_mode && $tc eq "NO"} {
      STOP_spindle_coolant
    }

    # Home / reset at the end of every operation (after M9/M5)
    # - not after SHIMSET ops (tool stays at the part for the following cut)
    if {!$shimset_mode} {
      MOM_output_literal "L_M54"
    }

    # Wear offset tolerance check - TURN GAGECUT ops only, after everything
    global mom_operation_name
    if {$shimset_turn_op && !$shimset_mode && \
        [string match -nocase "*GAGECUT*" $mom_operation_name]} {
      GAGE_output_wear_check
    }

    set shimset_mode    0
    set shimset_turn_op 0
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
set TURN_FEED_DEBUG 1
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
  MOM_output_literal "MSG(\"${axis} ASINMA OFSETI TOLERANS DISI (+/-${t}) - KONTROL ET\")"
  MOM_output_literal "M0"
  MOM_output_literal "GOTOB ${lbl}"
  MOM_output_literal "ENDIF"
}

proc GAGE_output_wear_check {} {
  global gage_tol_x gage_tol_z GAGE_WEAR_DP_X GAGE_WEAR_DP_Z
  if {$gage_tol_x eq "" && $gage_tol_z eq ""} { return }   ;# no UDE in this op

  MOM_output_literal ";*** ASINMA OFSET TOLERANS KONTROLU ***"
  GAGE_output_axis X $GAGE_WEAR_DP_X $gage_tol_x
  GAGE_output_axis Z $GAGE_WEAR_DP_Z $gage_tol_z
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
