#=============================================================================
# SHIMSET handling - custom layer
#  - SHIMSET ops: no spindle start, no coolant on
#  - Every other op: spindle + coolant ON at start, OFF at end
#=============================================================================

#-----------------------------------------------------------------------------
# Helpers
#-----------------------------------------------------------------------------
proc get_next_oper_name {} {
  global mom_operation_name mom_operation_name_list
  if {![info exists mom_operation_name_list]} { return "" }
  set ops [regexp -all -inline {\S+} $mom_operation_name_list]
  set i [lsearch -exact $ops $mom_operation_name]
  if {$i < 0 || $i + 1 >= [llength $ops]} { return "" }
  return [lindex $ops [expr {$i + 1}]]
}

proc SHIMSET_start {} {
  global mom_operation_name shimset_mode
  set shimset_mode [string match -nocase "*SHIMSET*" $mom_operation_name]
}

proc STOP_spindle_coolant {} {
  SHIMSET_orig_LIB_WRITE_coolant off   ;# original, bypasses shimset wrapper
  LIB_SPINDLE_end
}

#-----------------------------------------------------------------------------
# Event wrappers (each event wrapped ONCE)
#-----------------------------------------------------------------------------
if {![llength [info commands SHIMSET_orig_start_of_path]]} {
  rename MOM_start_of_path SHIMSET_orig_start_of_path
  proc MOM_start_of_path {} {
    global shimset_mode
    SHIMSET_start
    if {$shimset_mode} {
      # Block M8 from ANY template during shimset (e.g. initial_move_turn)
      MOM_disable_address M_coolant
    } else {
      # Guarantee M8 on the first coolant call, even if M_coolant looks modal
      MOM_force once M_coolant
    }
    SHIMSET_orig_start_of_path
  }
}

if {![llength [info commands SHIMSET_orig_end_of_path]]} {
  rename MOM_end_of_path SHIMSET_orig_end_of_path
  proc MOM_end_of_path {} {
    global mom_next_oper_has_tool_change shimset_mode

    SHIMSET_orig_end_of_path

    if {$shimset_mode} {
      MOM_enable_address M_coolant
    }

    # Library already stops spindle/coolant on tool/MCS change.
    # Otherwise stop them ourselves after every cutting (non-SHIMSET) op.
    set tc [expr {[info exists mom_next_oper_has_tool_change] \
                  ? $mom_next_oper_has_tool_change : "YES"}]
    if {!$shimset_mode && $tc eq "NO"} {
      STOP_spindle_coolant
    }

    set shimset_mode 0
  }
}

#-----------------------------------------------------------------------------
# Library proc wrappers
#-----------------------------------------------------------------------------
if {![llength [info commands SHIMSET_orig_LIB_SPINDLE_start]]} {
  rename LIB_SPINDLE_start SHIMSET_orig_LIB_SPINDLE_start
  proc LIB_SPINDLE_start {args} {
    global shimset_mode
    if {[info exists shimset_mode] && $shimset_mode} { return }
    return [uplevel 1 [list SHIMSET_orig_LIB_SPINDLE_start {*}$args]]
  }
}

if {![llength [info commands SHIMSET_orig_LIB_WRITE_coolant]]} {
  rename LIB_WRITE_coolant SHIMSET_orig_LIB_WRITE_coolant
  proc LIB_WRITE_coolant {args} {
    global shimset_mode
    if {[info exists shimset_mode] && $shimset_mode \
        && [lindex $args 0] eq "on"} {
      return
    }
    return [uplevel 1 [list SHIMSET_orig_LIB_WRITE_coolant {*}$args]]
  }
}
