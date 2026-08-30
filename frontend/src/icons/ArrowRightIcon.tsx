import { ReactComponent as ArrowRightFromArcSvg } from '@/assets/icon/arrow-right-from-arc.svg';

export default function ArrowRightIcon({ className, onClick }) {
  return (
    <span className={className}>
      <ArrowRightFromArcSvg onClick={onClick} />
    </span>
  );
}
