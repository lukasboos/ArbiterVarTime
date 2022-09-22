/**import boom.exu

/**
 * Arbiter Control determining which producer has access
 */
private object ArbiterCtrlVarTime {
  def apply(request: ExeUnitResp): Seq[Bool] = {
    request.map(_.valid).length match{
      case 0 => Seq()
      case 1 => Seq(true.B)
      case _ => varTime(request)
    }


    /** true.B +: request.tail.init.scanLeft(request.head)(_ || _).map(!_) */
  }


  def varTime(request: ExeUnitResp): Seq[Bool] = {
    //val newReq: Seq[Bool] = Seq.empty[bool]
    val oldest = Seq()
    oldest :+ request(request.length-1)
    //find oldest in request
    for (i <- 0 to request.length-1 by +1) {
      if (IsOlder(request(i).uop.rob_idx, oldest(0).uop.rob_idx, rob.io.rob_head_idx)){
        oldest +: request.slice(i,i)
      }
    }
    //after loop oldest is oldest(0)
    return oldest
  }
}

/**
 *Arbiter with variable time execution
 *edited default case in Arbiterctl
 */
class ArbiterVarTime[T <: Data](val gen: T, val n: Int) extends Module {
  val io = IO(new ArbiterIO(gen, n))

  io.chosen := (n - 1).asUInt
  io.out.bits := io.in(n - 1).bits
  for (i <- n - 2 to 0 by -1) {
    when(io.in(i).valid) {
      io.chosen := i.asUInt
      io.out.bits := io.in(i).bits
    }
  }

  val grant = ArbiterCtrlVarTime(io.in)
  for ((in, g) <- io.in.zip(grant))
    in.ready := g && io.out.ready
  io.out.valid := !grant.last || io.in.last.valid
}
*/


//########################
class Queue[T <: boom.common.HasBoomUOP](gen: T, entries: Int, inputs: Int, flush_fn: boom.common.MicroOp => Bool = u => true.B, flow: Boolean = true)
                                        (implicit p: freechips.rocketchip.config.Parameters)
  extends boom.common.BoomModule()(p)
    with boom.common.HasBoomCoreParameters
{
  val io = IO(new Bundle {
    val enq     = Vec(inputs, Flipped(Decoupled(gen))) //Handshake signals
    val deq     = Decoupled(gen)

    val brupdate  = Input(new BrUpdateInfo())
    val flush   = Input(Bool())

    val empty   = Output(Bool())
    val count   = Output(UInt(log2Ceil(entries).W))

    //edited
    val rob_head = Input(UInt(32.W))
  })

  //val enqwire = WireInit(0.U)//Wire(UInt())
  //put oldest queue signal to out
  val oldest = RegInit(0.U)

  val ram     = Mem(entries, gen)
  val valids  = RegInit(VecInit(Seq.fill(entries) {false.B}))
  val uops    = Reg(Vec(entries, new MicroOp))

  val enq_ptr = Counter(entries)
  val deq_ptr = Counter(entries)
  val maybe_full = RegInit(false.B)

  val ptr_match = enq_ptr.value === deq_ptr.value
  io.empty := ptr_match && !maybe_full
  val full = ptr_match && maybe_full
  val do_enq = WireInit(VecInit(Seq.fill(inputs) {io.enq(0).fire}))
  val do_deq = WireInit((io.deq.ready || !valids(deq_ptr.value)) && !io.empty)

  for (i <- 0 until entries) {
    val mask = uops(i).br_mask
    val uop  = uops(i)
    valids(i)  := valids(i) && !IsKilledByBranch(io.brupdate, mask) && !(io.flush && flush_fn(uop))
    when (valids(i)) {
      uops(i).br_mask := GetNewBrMask(io.brupdate, mask)
    }
  }

  for(i <- 0 until inputs){
    do_enq(i) := io.enq(i).fire
  }

  //enqwire := WireInit(0.U)
  val enqwire = VecInit(Seq.fill(inputs) {0.U})
  for(i <-1 until inputs){
    enqwire(i) := PopCount(do_enq.slice(0, i-1))
  }
  for(i <- 0 until inputs){

    when (do_enq(i)) {
      ram((enq_ptr.value + enqwire(i))%entries.asUInt)          := io.enq(i).bits
      valids((enq_ptr.value + enqwire(i))%entries.asUInt)       := true.B //!IsKilledByBranch(io.brupdate, io.enq.bits.uop)
      uops((enq_ptr.value + enqwire(i))%entries.asUInt)         := io.enq(i).bits.uop
      uops((enq_ptr.value + enqwire(i))%entries.asUInt).br_mask := GetNewBrMask(io.brupdate, io.enq(i).bits.uop)
      //enqwire = PopCount(do_enq(0), do_enq(i-1))
    }
  }
  enq_ptr.value := (enq_ptr.value + enqwire(inputs-1))%entries.asUInt



  when (do_deq) {
    valids(oldest) := false.B
    //deq_ptr.inc()
  }

  for(i <- 0 until inputs) {
    when(do_enq(i) =/= do_deq) {
      maybe_full := do_enq(i)
    }
  }

  for(i <- 0 until inputs) {
    io.enq(i).ready := !full
  }

  val out = Wire(gen)
  out             := ram(deq_ptr.value)
  out.uop         := uops(deq_ptr.value)
  io.deq.valid            := !io.empty && valids(deq_ptr.value) && !IsKilledByBranch(io.brupdate, out.uop) && !(io.flush && flush_fn(out.uop))
  io.deq.bits             := out
  io.deq.bits.uop.br_mask := GetNewBrMask(io.brupdate, out.uop)

  when(!valids(deq_ptr.value) && !io.empty){
    deq_ptr.inc()
  }

//find oldest value in queue
  for (i <- 0 until entries) {
    when(valids(i)){
      when(IsOlder(uops(i).rob_idx, uops(oldest).rob_idx, io.rob_head)){
        out             := ram(i)
        out.uop         := uops(i)
        io.deq.valid            := !io.empty && valids(i) && !IsKilledByBranch(io.brupdate, out.uop) && !(io.flush && flush_fn(out.uop))
        io.deq.bits             := out
        io.deq.bits.uop.br_mask := GetNewBrMask(io.brupdate, out.uop)
        oldest := i.asUInt//update oldest
      }
    }
  }
}
