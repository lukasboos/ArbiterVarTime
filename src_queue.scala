class Queue[T <: boom.common.HasBoomUOP](gen: T, entries: Int, flush_fn: boom.common.MicroOp => Bool = u => true.B, flow: Boolean = true)
                                        (implicit p: freechips.rocketchip.config.Parameters)
  extends boom.common.BoomModule()(p)
    with boom.common.HasBoomCoreParameters
{
  val io = IO(new Bundle {
    val enq     = Flipped(Decoupled(gen)) //Handshake signals
    val deq     = Decoupled(gen)

    val brupdate  = Input(new BrUpdateInfo())
    val flush   = Input(Bool())

    val empty   = Output(Bool())
    val count   = Output(UInt(log2Ceil(entries).W))
  })

  val ram     = Mem(entries, gen)
  val valids  = RegInit(VecInit(Seq.fill(entries) {false.B}))
  val uops    = Reg(Vec(entries, new MicroOp))

  val enq_ptr = Counter(entries)
  val deq_ptr = Counter(entries)
  val maybe_full = RegInit(false.B)

  val ptr_match = enq_ptr.value === deq_ptr.value
  io.empty := ptr_match && !maybe_full
  val full = ptr_match && maybe_full
  val do_enq = WireInit(io.enq.fire)
  val do_deq = WireInit((io.deq.ready || !valids(deq_ptr.value)) && !io.empty)

  for (i <- 0 until entries) {
    val mask = uops(i).br_mask
    val uop  = uops(i)
    valids(i)  := valids(i) && !IsKilledByBranch(io.brupdate, mask) && !(io.flush && flush_fn(uop))
    when (valids(i)) {
      uops(i).br_mask := GetNewBrMask(io.brupdate, mask)
    }
  }

  when (do_enq) {
    ram(enq_ptr.value)          := io.enq.bits
    valids(enq_ptr.value)       := true.B //!IsKilledByBranch(io.brupdate, io.enq.bits.uop)
    uops(enq_ptr.value)         := io.enq.bits.uop
    uops(enq_ptr.value).br_mask := GetNewBrMask(io.brupdate, io.enq.bits.uop)
    enq_ptr.inc()
  }

  when (do_deq) {
    valids(deq_ptr.value) := false.B
    deq_ptr.inc()
  }

  when (do_enq =/= do_deq) {
    maybe_full := do_enq
  }

  io.enq.ready := !full

  val out = Wire(gen)
  out             := ram(deq_ptr.value)
  out.uop         := uops(deq_ptr.value)
  io.deq.valid            := !io.empty && valids(deq_ptr.value) && !IsKilledByBranch(io.brupdate, out.uop) && !(io.flush && flush_fn(out.uop))
  io.deq.bits             := out
  io.deq.bits.uop.br_mask := GetNewBrMask(io.brupdate, out.uop)
}
