/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.bytecodeAnalysis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.org.objectweb.asm.tree.analysis.AnalyzerException;

import java.util.*;

final class ELattice<T extends Enum<T>> {
  final T bot;
  final T top;

  ELattice(T bot, T top) {
    this.bot = bot;
    this.top = top;
  }

  final T join(T x, T y) {
    if (x == bot) return y;
    if (y == bot) return x;
    if (x == y) return x;
    return top;
  }

  final T meet(T x, T y) {
    if (x == top) return y;
    if (y == top) return x;
    if (x == y) return x;
    return bot;
  }
}


class ResultUtil<Id, T extends Enum<T>> {
  private final ELattice<T> lattice;
  final T top;
  ResultUtil(ELattice<T> lattice) {
    this.lattice = lattice;
    top = lattice.top;
  }

  Result<Id, T> join(Result<Id, T> r1, Result<Id, T> r2) throws AnalyzerException {
    if (r1 instanceof Final && ((Final) r1).value == top) {
      return r1;
    }
    if (r2 instanceof Final && ((Final) r2).value == top) {
      return r2;
    }
    if (r1 instanceof Final && r2 instanceof Final) {
      return new Final<Id, T>(lattice.join(((Final<?, T>) r1).value, ((Final<?, T>) r2).value));
    }
    if (r1 instanceof Final && r2 instanceof Pending) {
      Final<?, T> f1 = (Final<?, T>)r1;
      Pending<Id, T> pending = (Pending<Id, T>) r2;
      Set<Product<Id, T>> sum1 = new HashSet<Product<Id, T>>(pending.sum);
      sum1.add(new Product<Id, T>(f1.value, Collections.<Id>emptySet()));
      return new Pending<Id, T>(sum1);
    }
    if (r1 instanceof Pending && r2 instanceof Final) {
      Final<?, T> f2 = (Final<?, T>)r2;
      Pending<Id, T> pending = (Pending<Id, T>) r1;
      Set<Product<Id, T>> sum1 = new HashSet<Product<Id, T>>(pending.sum);
      sum1.add(new Product<Id, T>(f2.value, Collections.<Id>emptySet()));
      return new Pending<Id, T>(sum1);
    }
    Pending<Id, T> pending1 = (Pending<Id, T>) r1;
    Pending<Id, T> pending2 = (Pending<Id, T>) r2;
    Set<Product<Id, T>> sum = new HashSet<Product<Id, T>>();
    sum.addAll(pending1.sum);
    sum.addAll(pending2.sum);
    checkLimit(sum);
    return new Pending<Id, T>(sum);
  }

  private void checkLimit(Set<Product<Id, T>> sum) throws AnalyzerException {
    int size = 0;
    for (Product<Id, T> prod : sum) {
      size += prod.ids.size();
    }
    if (size > Analysis.EQUATION_SIZE_LIMIT) {
      throw new AnalyzerException(null, "Equation size is too big");
    }
  }
}

final class Product<K, V> {
  @NotNull final V value;
  @NotNull final Set<K> ids;

  Product(@NotNull V value, @NotNull Set<K> ids) {
    this.value = value;
    this.ids = ids;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    Product product = (Product)o;

    if (!ids.equals(product.ids)) return false;
    if (!value.equals(product.value)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = value.hashCode();
    result = 31 * result + ids.hashCode();
    return result;
  }
}

interface Result<Id, T> {}
final class Final<Id, T> implements Result<Id, T> {
  final T value;
  Final(T value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return "Final{" + "value=" + value + '}';
  }
}

final class Pending<Id, T> implements Result<Id, T> {
  final Set<Product<Id, T>> sum;

  Pending(Set<Product<Id, T>> sum) {
    this.sum = sum;
  }

}

final class Solution<Id, Val> {
  final Id id;
  final Val value;

  Solution(Id id, Val value) {
    this.id = id;
    this.value = value;
  }
}

final class Equation<Id, T> {
  final Id id;
  final Result<Id, T> rhs;

  Equation(Id id, Result<Id, T> rhs) {
    this.id = id;
    this.rhs = rhs;
  }

  @Override
  public String toString() {
    return "Equation{" + "id=" + id + ", rhs=" + rhs + '}';
  }
}

final class Solver {

  private int size = 0;
  private final ELattice<Value> lattice;
  private final HashMap<HKey, HashSet<HKey>> dependencies = new HashMap<HKey, HashSet<HKey>>();
  private final HashMap<HKey, HPending> pending = new HashMap<HKey, HPending>();
  private final HashMap<HKey, Value> solved = new HashMap<HKey, Value>();
  private final Stack<HKey> moving = new Stack<HKey>();

  int getSize() {
    return size;
  }

  Solver(ELattice<Value> lattice) {
    this.lattice = lattice;
  }

  private final HashSet<HKey> queued = new HashSet<HKey>();

  void addEquation(HEquation equation) {
    size ++;
    HResult rhs = equation.result;
    if (!queued.add(equation.key)) {
      ProjectBytecodeAnalysis.LOG.error("duplicate");
    }
    if (rhs instanceof HFinal) {
      solved.put(equation.key, ((HFinal) rhs).value);
      moving.push(equation.key);
    } else if (rhs instanceof HPending) {
      HPending pendResult = ((HPending)rhs).copy();
      HResult norm = normalize(pendResult.delta);
      if (norm instanceof HFinal) {
        solved.put(equation.key, ((HFinal) norm).value);
        moving.push(equation.key);
      }
      else {
        HPending pendResult1 = ((HPending)rhs).copy();
        for (HComponent component : pendResult1.delta) {
          for (HKey trigger : component.ids) {
            HashSet<HKey> set = dependencies.get(trigger);
            if (set == null) {
              set = new HashSet<HKey>();
              dependencies.put(trigger, set);
            }
            set.add(equation.key);
          }
          pending.put(equation.key, pendResult1);
        }
      }
    }
  }

  HashMap<HKey, Value> solve() {
    while (!moving.empty()) {
      HKey id = moving.pop();
      Value value = solved.get(id);

      HKey[] pIds  = id.stable ? new HKey[]{id, id.negate()} : new HKey[]{id.negate(), id};
      Value[] pVals = id.stable ? new Value[]{value, value} : new Value[]{value, lattice.top};

      for (int i = 0; i < pIds.length; i++) {
        HKey pId = pIds[i];
        Value pVal = pVals[i];
        HashSet<HKey> dIds = dependencies.get(pId);
        if (dIds == null) {
          continue;
        }
        for (HKey dId : dIds) {
          HPending pend = pending.remove(dId);
          if (pend != null) {
            HResult pend1 = substitute(pend, pId, pVal);
            if (pend1 instanceof HFinal) {
              HFinal fi = (HFinal)pend1;
              solved.put(dId, fi.value);
              moving.push(dId);
            }
            else {
              pending.put(dId, (HPending)pend1);
            }
          }
        }
      }
    }
    pending.clear();
    return solved;
  }

  // substitute id -> value into pending
  HResult substitute(@NotNull HPending pending, @NotNull HKey id, @NotNull Value value) {
    HComponent[] sum = pending.delta;
    for (HComponent intIdComponent : sum) {
      if (intIdComponent.remove(id)) {
        intIdComponent.value = lattice.meet(intIdComponent.value, value);
      }
    }
    return normalize(sum);
  }

  @NotNull HResult normalize(@NotNull HComponent[] sum) {
    Value acc = lattice.bot;
    boolean computableNow = true;
    for (HComponent prod : sum) {
      if (prod.isEmpty() || prod.value == lattice.bot) {
        acc = lattice.join(acc, prod.value);
      } else {
        computableNow = false;
      }
    }
    return (acc == lattice.top || computableNow) ? new HFinal(acc) : new HPending(sum);
  }

}
